'use strict';

function createTrafficChart(formatSpeed, formatTicks) {
    const plot = document.getElementById('traffic-plot');
    const empty = document.getElementById('traffic-empty');
    if (typeof uPlot !== 'function') {
        empty.textContent = 'Traffic chart unavailable';
        return null;
    }

    const ranges = [...document.querySelectorAll('#traffic-ranges button')];
    const tooltip = document.getElementById('traffic-tooltip');
    const tooltipTime = document.getElementById('traffic-tooltip-time');
    const tooltipUp = document.getElementById('traffic-tooltip-up');
    const tooltipDown = document.getElementById('traffic-tooltip-down');
    const timeFormat = new Intl.DateTimeFormat('en-US', {
        hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
    });
    const colors = getComputedStyle(plot);
    const height = 210;
    const gapMillis = 2500;
    let minutes = 1;
    let sampleTime = Date.now();
    let history = [];
    let pointer = null;

    function nearbyPoint(chart, left) {
        if (left < 0 || !chart.data[0].length) return null;
        const target = chart.posToVal(left, 'x');
        const index = chart.valToIdx(target);
        return chart.data[1][index] != null && Math.abs(chart.data[0][index] - target) <= gapMillis / 2000
            ? index : null;
    }

    function showTooltip(chart) {
        const index = nearbyPoint(chart, chart.cursor.left);
        tooltip.hidden = index == null;
        if (index == null) return;
        const time = new Date(Math.round(chart.data[0][index] * 1000));
        tooltipTime.dateTime = time.toISOString();
        tooltipTime.textContent = timeFormat.format(time);
        tooltipUp.textContent = formatSpeed(chart.data[1][index]);
        tooltipDown.textContent = formatSpeed(chart.data[2][index]);
        const left = chart.cursor.left;
        const top = chart.cursor.top;
        tooltip.style.left = Math.max(0, Math.min(left + 10, chart.over.clientWidth - tooltip.offsetWidth)) + 'px';
        tooltip.style.top = Math.max(0, Math.min(top + 10, chart.over.clientHeight - tooltip.offsetHeight)) + 'px';
    }

    const chart = new uPlot({
        width: Math.max(1, plot.clientWidth),
        height,
        legend: {show: false},
        cursor: {
            drag: {x: false, y: false},
            move: (chart, left, top) => {
                const index = nearbyPoint(chart, left);
                return [index == null ? left : chart.valToPos(chart.data[0][index], 'x'), top];
            }
        },
        series: [
            {},
            {label: 'Upload', stroke: colors.getPropertyValue('--traffic-upload').trim(), width: 2, spanGaps: false},
            {label: 'Download', stroke: colors.getPropertyValue('--traffic-download').trim(), width: 2, spanGaps: false}
        ],
        axes: [
            {space: 80, values: (_, ticks) => ticks.map(tick => timeFormat.format(tick * 1000))},
            {size: 90, values: (_, ticks) => formatTicks(ticks)}
        ],
        hooks: {setCursor: [showTooltip]}
    }, [[], [], []], plot);
    chart.over.append(tooltip);
    chart.over.addEventListener('mousemove', event => {
        pointer = {x: event.clientX, y: event.clientY};
    });
    chart.over.addEventListener('mouseleave', () => { pointer = null; });

    function redraw() {
        const start = sampleTime - minutes * 60000;
        const times = [], upload = [], download = [];
        let previous = null;
        let peak = 0;
        for (const point of history) {
            if (point.sampleTime <= start) continue;
            if (previous != null && point.sampleTime - previous > gapMillis) {
                times.push((previous + point.sampleTime) / 2000);
                upload.push(null);
                download.push(null);
            }
            times.push(point.sampleTime / 1000);
            upload.push(point.uploadBps);
            download.push(point.downloadBps);
            peak = Math.max(peak, point.uploadBps, point.downloadBps);
            previous = point.sampleTime;
        }
        empty.hidden = times.length > 0;
        chart.batch(() => {
            chart.setData([times, upload, download], false);
            chart.setScale('x', {min: start / 1000, max: sampleTime / 1000});
            chart.setScale('y', {min: 0, max: peak > 0 ? peak * 1.1 : 1});
            chart.redraw(false, true);
        });
        if (pointer) queueMicrotask(() => {
            if (!pointer) return;
            const bounds = chart.over.getBoundingClientRect();
            const left = pointer.x - bounds.left, top = pointer.y - bounds.top;
            const inside = left >= 0 && left <= bounds.width && top >= 0 && top <= bounds.height;
            chart.setCursor(inside ? {left, top} : {left: -10, top: -10});
        });
    }

    for (const button of ranges) {
        button.onclick = () => {
            minutes = Number(button.dataset.minutes);
            for (const option of ranges) {
                const selected = option === button;
                option.setAttribute('aria-pressed', selected);
                option.classList.toggle('selected', selected);
            }
            redraw();
        };
    }
    new ResizeObserver(() => {
        const width = Math.max(1, plot.clientWidth);
        if (width !== chart.width) chart.setSize({width, height});
    }).observe(plot);

    return {
        update(time, points) {
            sampleTime = time;
            history = points;
            redraw();
        },
        redraw
    };
}
