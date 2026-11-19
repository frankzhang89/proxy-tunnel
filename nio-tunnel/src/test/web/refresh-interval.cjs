// Called by connection-table.cjs with the same Playwright browser.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

module.exports = async function testRefreshInterval(browser) {
    const page = await browser.newPage({viewport: {width: 1560, height: 1050}});
    const requests = [], errors = [];
    const storageKey = 'nio-tunnel.refreshIntervalSeconds';
    let failNext = false, onHold, release;
    page.on('pageerror', error => errors.push(error.message));
    const sample = {
        sampleTime: 0, serverPid: 43210, httpPort: 8380, socksPort: 1080,
        upstream: {host: 'proxy.example.com', port: 443}, processStatus: 'ready',
        summary: {activeClients: 0, activeFlows: 0, directFlows: 0, upstreamFlows: 0,
            uploadBytes: 1024, downloadBytes: 2048, uploadBps: 128, downloadBps: 256},
        pagination: {page: 1, pageSize: 50, total: 0, pages: 1}, connections: []
    };
    await page.route('http://monitor.test/**', async route => {
        const url = new URL(route.request().url());
        if (url.pathname === '/api/snapshot') {
            const query = Object.fromEntries(url.searchParams);
            requests.push(query);
            if (onHold) {
                const started = onHold;
                onHold = null;
                await new Promise(resolve => { release = resolve; started(); });
            }
            if (failNext) {
                failNext = false;
                return route.fulfill({status: 503, body: ''});
            }
            return route.fulfill({json: {...sample, pagination: {...sample.pagination, pageSize: Number(query.pageSize)}}});
        }
        const name = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
        return route.fulfill({
            contentType: name.endsWith('.js') ? 'application/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html',
            body: fs.readFileSync(path.resolve(__dirname, '../../main/resources/web', name))
        });
    });
    const idle = () => page.waitForFunction(() => document.getElementById('connections').getAttribute('aria-busy') === 'false');
    const tick = milliseconds => page.clock.runFor(milliseconds);
    const field = page.locator('#refresh-interval');
    const status = page.locator('#refresh');
    const saved = () => page.evaluate(key => localStorage.getItem(key), storageKey);
    const commit = async (value, event = 'enter') => {
        await field.fill(value);
        if (event === 'enter') await field.press('Enter');
        else await field.press('Tab');
    };
    const holdNext = () => new Promise(resolve => { onHold = resolve; });
    const setHidden = hidden => page.evaluate(value => {
        Object.defineProperty(document, 'hidden', {configurable: true, value});
        document.dispatchEvent(new Event('visibilitychange'));
    }, hidden);

    try {
        const start = new Date('2026-01-01T00:00:00Z');
        await page.clock.install({time: start});
        await page.clock.pauseAt(new Date(start.getTime() + 1000));
        await page.goto('http://monitor.test/');
        await idle();
        assert.equal(await field.inputValue(), '1');
        assert.match(await status.textContent(), /Updates every second/);
        assert.equal(await field.getAttribute('list'), 'refresh-interval-options');
        assert.deepEqual(await page.locator('#refresh-interval-options option').evaluateAll(options => options.map(option => option.value)), ['1', '2', '5', '10', '30']);

        // Editing is a draft; successful automatic responses must not overwrite it.
        let count = requests.length;
        await field.fill('3');
        await tick(1000); await idle();
        assert.equal(requests.length, count + 1);
        assert.equal(await field.inputValue(), '3');
        assert.match(await status.textContent(), /Updates every second/);

        // Both submission paths use one timer, with no immediate fetch.
        for (const [index, seconds] of [1, 2, 5, 10, 30, 3, 15, 60].entries()) {
            count = requests.length;
            await commit(String(seconds), index % 2 ? 'change' : 'enter');
            assert.equal(requests.length, count);
            assert.equal(await saved(), String(seconds));
            assert.match(await status.textContent(), seconds === 1 ? /Updates every second/ : new RegExp(`Updates every ${seconds} seconds`));
            await tick(seconds * 1000 - 1);
            assert.equal(requests.length, count);
            await tick(1); await idle();
            assert.equal(requests.length, count + 1);
        }
        for (const seconds of ['3', '5', '2']) await commit(seconds);
        count = requests.length;
        await tick(2000); await idle();
        await tick(2000); await idle();
        assert.equal(requests.length, count + 2);

        // Invalid values leave the last valid timer and storage untouched.
        for (const value of ['', '0', '-1', '1.5', 'abc', '1e3', '+2', 'Infinity', '2147484', '9007199254740993']) {
            count = requests.length;
            await commit(value);
            assert.equal(await field.getAttribute('aria-invalid'), 'true');
            assert.equal(await page.locator('#refresh-interval-error').isVisible(), true);
            assert.equal(await saved(), '2');
            await tick(2000); await idle();
            assert.equal(requests.length, count + 1);
            assert.equal(await field.inputValue(), value);
            assert.match(await status.textContent(), /Updates every 2 seconds/);
        }
        await commit('2147483');
        assert.equal(await saved(), '2147483');
        await commit(' 003 ');
        assert.equal(await field.inputValue(), '3');
        assert.equal(await field.getAttribute('aria-invalid'), 'false');
        assert.equal(await page.locator('#refresh-interval-error').isVisible(), false);

        // Changes during pause stay paused; manual actions still fetch immediately.
        await page.locator('#pause').click();
        count = requests.length;
        await commit('15');
        await tick(45000);
        assert.equal(requests.length, count);
        assert.match(await status.textContent(), /Page updates paused/);
        await page.locator('th[data-sort="pid"] button').click(); await idle();
        assert.equal(requests.length, count + 1);
        assert.equal(requests.at(-1).sort, 'pid');
        assert.equal('refreshIntervalSeconds' in requests.at(-1), false);
        await page.locator('#page-size').selectOption('100'); await idle();
        assert.equal(requests.length, count + 2);

        // A failed query rolls back query controls but leaves the interval alone.
        failNext = true;
        await page.locator('#page-size').selectOption('200'); await idle();
        assert.equal(await page.locator('#page-size').inputValue(), '100');
        assert.match(await status.textContent(), /Updates interrupted/);
        const errorStatus = await status.textContent();
        count = requests.length;
        await commit('3');
        assert.equal(await status.textContent(), errorStatus);
        assert.equal(await saved(), '3');
        await tick(6000);
        assert.equal(requests.length, count);
        await page.locator('#pause').click(); await idle();
        assert.equal(requests.length, count + 1);
        await tick(2999);
        assert.equal(requests.length, count + 1);
        await tick(1); await idle();
        assert.equal(requests.length, count + 2);

        // Hidden-page ticks are skipped; becoming visible only fetches if unpaused.
        await setHidden(true);
        count = requests.length;
        await tick(9000);
        assert.equal(requests.length, count);
        await setHidden(false); await idle();
        assert.equal(requests.length, count + 1);
        await page.locator('#pause').click();
        await setHidden(true); await setHidden(false);
        assert.equal(requests.length, count + 1);
        await page.locator('#pause').click(); await idle();

        // An interval change neither aborts an in-flight request nor overlaps it.
        await commit('1');
        let started = holdNext();
        count = requests.length;
        await tick(1000); await started;
        await commit('2');
        await tick(2000);
        assert.equal(requests.length, count + 1);
        assert.equal(await page.locator('#connections').getAttribute('aria-busy'), 'true');
        release(); await idle();
        assert.match(await status.textContent(), /Updates every 2 seconds/);
        await tick(2000); await idle();
        assert.equal(requests.length, count + 2);

        // The five-second timeout still applies after changing the interval.
        await commit('1');
        started = holdNext();
        count = requests.length;
        await tick(1000); await started;
        await commit('2');
        await tick(5000); await idle();
        assert.equal(requests.length, count + 1);
        assert.match(await status.textContent(), /Updates interrupted/);
        release();

        // Changing the interval does not clear the failure retry deadline.
        await commit('1');
        await tick(1000);
        assert.equal(requests.length, count + 1);
        await tick(1000); await idle();
        assert.equal(requests.length, count + 2);
        failNext = true;
        await tick(1000); await idle();
        count = requests.length;
        await commit('1');
        await tick(1000);
        assert.equal(requests.length, count);
        await tick(1000); await idle();
        assert.equal(requests.length, count + 1);

        await commit('60');
        await page.reload(); await idle();
        assert.equal(await field.inputValue(), '60');
        assert.match(await status.textContent(), /Updates every 60 seconds/);
        count = requests.length;
        await tick(59999);
        assert.equal(requests.length, count);
        await tick(1); await idle();
        assert.equal(requests.length, count + 1);
        for (const invalid of ['', '0', '-1', '1.5', 'bad', '2147484']) {
            await page.evaluate(([key, value]) => localStorage.setItem(key, value), [storageKey, invalid]);
            await page.reload(); await idle();
            assert.equal(await field.inputValue(), '1');
            assert.match(await status.textContent(), /Updates every second/);
        }

        // Exercise unavailable storage reads and writes without disabling updates.
        await page.addInitScript(() => {
            Object.defineProperty(window, 'localStorage', {get() { throw new DOMException('Storage is disabled', 'SecurityError'); }});
        });
        await page.reload(); await idle();
        assert.equal(await field.inputValue(), '1');
        await commit('3');
        count = requests.length;
        await tick(3000); await idle();
        assert.equal(requests.length, count + 1);
        assert.match(await status.textContent(), /Updates every 3 seconds/);

        // Include both the regular and error states at a narrow viewport.
        await page.setViewportSize({width: 520, height: 900});
        for (const value of ['60', '0']) {
            await commit(value);
            const bounds = await page.locator('.refresh-control').boundingBox();
            assert(bounds.x >= 0 && bounds.x + bounds.width <= 520);
            assert((await field.boundingBox()).width < 160);
        }
        assert.deepEqual(errors, []);
        console.log('PASS: refresh presets/custom input, validation, timer cadence, persistence, pause, visibility, rollback, slow requests, timeout, backoff, unavailable storage and narrow layout.');
    } finally {
        if (release) release();
        await page.close();
    }
};
