const assert = require('node:assert/strict');
const path = require('node:path');

module.exports = async function testSpeedUnits(page, {holdNext, requests}) {
    const toggle = page.getByRole('switch', {name: 'Use bit/s for speeds'});
    const units = page.locator('#units');
    const idle = () => page.locator('#connections[aria-busy="false"]').waitFor({state: 'attached'});
    const chartState = () => page.evaluate(() => {
        const chart = window.testTrafficChart;
        return {data: chart.data, min: chart.scales.x.min, max: chart.scales.x.max};
    });
    const axis = values => page.evaluate(values => window.testTrafficChart.axes[1].values(window.testTrafficChart, values), values);
    const hover = async () => {
        const over = page.locator('#traffic-plot .u-over');
        await over.scrollIntoViewIfNeeded();
        const bounds = await over.boundingBox();
        const left = await page.evaluate(() => {
            const chart = window.testTrafficChart;
            return chart.valToPos(chart.scales.x.max - 10, 'x');
        });
        await page.mouse.move(bounds.x + left, bounds.y + bounds.height / 2);
        await page.clock.runFor(32);
    };

    await page.setViewportSize({width: 1560, height: 1050});
    assert.equal(await toggle.isChecked(), false);
    assert.equal(await page.locator('header #refresh-control').count(), 1);
    assert.equal(await page.locator('nav #refresh-control').count(), 0);
    assert.equal(await page.locator('nav #speed-unit').count(), 1);
    assert.deepEqual(await page.locator('.header-controls').evaluate(node => [...node.children].map(child => child.id)), ['refresh-control', 'refresh', 'address']);
    await page.locator('#pause').click();
    await units.selectOption('Auto');
    await page.locator('#rows tr').first().click();
    const selected = await page.locator('#rows tr.chosen').getAttribute('data-id');
    const rowIds = await page.locator('#rows tr').evaluateAll(rows => rows.map(row => row.dataset.id));
    const total = await page.locator('#total').textContent();
    const details = await page.locator('#detail').textContent();
    const original = await chartState();
    const before = requests.length;
    await toggle.focus(); await toggle.press('Space');
    assert.equal(await toggle.isChecked(), true);
    assert.equal(await toggle.evaluate(node => getComputedStyle(node).outlineStyle), 'solid');
    assert.equal(await page.locator('#upload').textContent(), '8.00 kb/s');
    assert.equal(await page.locator('#download').textContent(), '16.00 kb/s');
    assert.equal(await page.locator('#total').textContent(), total);
    assert.equal(await page.locator('#detail').textContent(), details);
    assert.deepEqual(await units.locator('option').allTextContents(), ['Auto', 'b', 'kb', 'mb', 'gb']);
    assert.deepEqual(await axis([0, 128, 1024, 1048576, 1073741824]), ['0 b/s', '1 kb/s', '8 kb/s', '8 mb/s', '8 gb/s']);
    await hover();
    assert.equal(await page.locator('#traffic-tooltip-up').textContent(), '8.00 kb/s');
    assert.equal(await page.locator('#traffic-tooltip-down').textContent(), '16.00 kb/s');
    assert.deepEqual(await chartState(), original);
    assert.equal(await page.locator('#rows tr.chosen').getAttribute('data-id'), selected);
    assert.deepEqual(await page.locator('#rows tr').evaluateAll(rows => rows.map(row => row.dataset.id)), rowIds);
    const raw = await page.locator('#rows tr').first().getAttribute('data-id');
    assert.equal(await page.locator('#rows tr').first().locator('td').nth(6).textContent(), `${(Number(raw) * 8 / 1024).toFixed(2)} kb/s`);

    for (const [unit, expected] of [['B', '8192 b/s'], ['KiB', '8.00 kb/s'], ['MiB', '0.01 mb/s'], ['GiB', '0.00 gb/s']]) {
        await units.selectOption(unit);
        assert.equal(await page.locator('#upload').textContent(), expected);
        const byteTotal = await page.locator('#total').textContent();
        const byteDetails = await page.locator('#detail').textContent();
        await toggle.uncheck();
        assert.equal(await units.inputValue(), unit);
        assert.deepEqual(await units.locator('option').allTextContents(), ['Auto', 'B', 'KiB', 'MiB', 'GiB']);
        assert.equal(await page.locator('#total').textContent(), byteTotal);
        assert.equal(await page.locator('#detail').textContent(), byteDetails);
        await toggle.check();
        assert.equal(await page.locator('#upload').textContent(), expected);
    }
    assert.equal(requests.length, before);

    // Mode changes neither restart the refresh deadline nor abort a pending request.
    await units.selectOption('Auto');
    await page.locator('#refresh-seconds').fill('5');
    await page.locator('#refresh-seconds').press('Enter');
    await page.locator('#pause').click(); await idle();
    const count = requests.length;
    await page.clock.runFor(3000);
    await toggle.uncheck();
    await page.clock.runFor(1999);
    assert.equal(requests.length, count);
    const release = holdNext();
    try {
        const pending = page.waitForRequest(request => new URL(request.url()).pathname === '/api/snapshot');
        await page.clock.runFor(1); await pending;
        await toggle.check();
        assert.equal(requests.length, count + 1);
        assert.equal(await page.locator('#connections').getAttribute('aria-busy'), 'true');
        assert.equal(await page.locator('#upload').textContent(), '8.00 kb/s');
    } finally { release(); }
    await idle();
    assert.equal(await page.locator('#upload').textContent(), '8.00 kb/s');
    await units.selectOption('MiB');
    await page.reload(); await idle();
    assert.equal(await toggle.isChecked(), true);
    assert.equal(await units.inputValue(), 'MiB');
    assert.equal(await units.locator('option:checked').textContent(), 'mb');
    for (const invalid of ['', 'invalid', 'BIT']) {
        await page.evaluate(value => localStorage.setItem('nio-tunnel.speedUnit', value), invalid);
        await page.reload(); await idle();
        assert.equal(await toggle.isChecked(), false);
        assert.equal(await units.locator('option:checked').textContent(), 'MiB');
    }

    // Verify header wrapping, switch usability and the dropdown's actual hit target.
    await units.selectOption('Auto');
    for (const width of [1560, 520, 375]) {
        await page.setViewportSize({width, height: 1050});
        await page.locator('#refresh-control').scrollIntoViewIfNeeded();
        const control = await page.locator('#refresh-control').boundingBox();
        assert(control.x >= 0 && control.x + control.width <= width);
        await page.getByRole('button', {name: 'Show refresh intervals'}).click();
        assert.equal(await page.locator('#refresh-options').isVisible(), true);
        assert.equal(await page.locator('#refresh-option-30').evaluate(node => {
            const box = node.getBoundingClientRect();
            return node.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2));
        }), true);
        await page.locator('#refresh-seconds').press('Escape');
        await toggle.check();
        const switchBounds = await toggle.boundingBox();
        assert(switchBounds.x >= 0 && switchBounds.x + switchBounds.width <= width);
        await page.screenshot({path: path.resolve(__dirname, `../../../target/speed-units-${width}.png`), fullPage: true});
    }

    await page.addInitScript(() => {
        const get = Storage.prototype.getItem, set = Storage.prototype.setItem;
        Storage.prototype.getItem = function(key) {
            if (key === 'nio-tunnel.speedUnit') throw new DOMException('Storage disabled', 'SecurityError');
            return get.call(this, key);
        };
        Storage.prototype.setItem = function(key, value) {
            if (key === 'nio-tunnel.speedUnit') throw new DOMException('Storage disabled', 'SecurityError');
            return set.call(this, key, value);
        };
    });
    await page.reload(); await idle();
    assert.equal(await toggle.isChecked(), false);
    await toggle.check();
    assert.equal(await page.locator('#upload').textContent(), '8.00 kb/s');
    console.log('PASS: speed bit/byte conversion, magnitudes, byte totals, chart labels/tooltip, pause, unchanged timing, pending requests, storage, header layout and dropdown stacking.');
};
