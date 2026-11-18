// Run with Playwright available through NODE_PATH (or PLAYWRIGHT_MODULE).
// This uses intercepted fixture data; Java tests cover server sorting and pagination.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const resources=path.resolve(__dirname,'../../main/resources/web');

async function testRefreshControl(page, {failNext, holdNext}) {
  const key = 'nio-tunnel.refreshIntervalSeconds';
  await page.addInitScript(() => {
    const fetch = window.fetch;
    window.snapshotRequests = 0;
    window.fetch = (url, options) => {
      if (url.startsWith('/api/snapshot?')) window.snapshotRequests++;
      return fetch(url, options);
    };
  });
  const start = new Date('2026-01-01T00:00:00Z');
  await page.clock.install({time: start});
  await page.clock.pauseAt(new Date(start.getTime() + 60000));
  await page.reload();

  const input = page.getByRole('combobox', {name: 'Refresh every'});
  const list = page.getByRole('listbox', {name: 'Refresh intervals'});
  const status = () => page.locator('#refresh').textContent();
  const settled = () => page.locator('#connections[aria-busy="false"]').waitFor({state: 'attached'});
  const requestCount = () => page.evaluate(() => window.snapshotRequests);
  const snapshot = () => page.waitForResponse(response => new URL(response.url()).pathname === '/api/snapshot');
  const noRequest = async (delay, advance = ms => page.clock.runFor(ms)) => {
    const before = await requestCount();
    await advance(delay);
    assert.equal(await requestCount(), before);
  };
  const requestAfter = async (delay, expectedStatus = 200) => {
    const before = await requestCount();
    const response = snapshot();
    await page.clock.runFor(delay);
    assert.equal(await requestCount(), before + 1);
    assert.equal((await response).status(), expectedStatus);
    await settled();
  };
  const enter = async value => {
    await input.fill(value);
    await input.press('Enter');
  };

  await settled();
  assert.equal(await input.inputValue(), '1');
  assert.equal(await requestCount(), 1);
  assert.match(await status(), /Updates every 1 s · .+/);

  for (const seconds of [1, 5, 10, 20, 30]) {
    await page.getByRole('button', {name: 'Show refresh intervals'}).click();
    assert.deepEqual(await list.getByRole('option').allTextContents(), ['1 s', '5 s', '10 s', '20 s', '30 s']);
    await list.getByRole('option', {name: seconds + ' s', exact: true}).click();
    assert.equal(await input.inputValue(), String(seconds));
    assert.equal(await input.getAttribute('aria-expanded'), 'false');
    assert.match(await status(), new RegExp(`Updates every ${seconds} s`));
    await noRequest(seconds * 1000 - 1);
    await requestAfter(1);
  }

  await input.press('ArrowUp');
  assert.equal(await input.getAttribute('aria-activedescendant'), 'refresh-option-30');
  await input.press('ArrowDown');
  await input.press('ArrowDown');
  assert.equal(await input.getAttribute('aria-activedescendant'), 'refresh-option-5');
  await input.press('Enter');
  assert.equal(await input.inputValue(), '5');
  await input.fill('007');
  await input.press('Tab');
  assert.equal(await input.inputValue(), '7');
  await noRequest(6999);
  await requestAfter(1);

  await input.selectText();
  await page.keyboard.type('5p');
  assert.equal(await input.inputValue(), '5');
  for (const invalid of ['0', '000', '-1', '1.5', ' 7', '7 ', '1e2', '+2', '１２', '1\n2']) {
    const cancelled = await input.evaluate((element, value) => {
      element.select();
      const data = new DataTransfer();
      data.setData('text/plain', value);
      return !element.dispatchEvent(new ClipboardEvent('paste', {clipboardData: data, cancelable: true}));
    }, invalid);
    assert.equal(cancelled, true);
    assert.equal(await input.inputValue(), '5');
  }
  await input.evaluate(element => {
    element.value = '8p';
    element.dispatchEvent(new InputEvent('input', {inputType: 'insertCompositionText'}));
  });
  assert.equal(await input.inputValue(), '5');
  await input.selectText();
  await input.press('Backspace');
  await input.press('0');
  assert.equal(await input.inputValue(), '');
  await input.press('Enter');
  assert.equal(await input.inputValue(), '7');
  await input.selectText();
  await input.press('Delete');
  await input.press('Tab');
  assert.equal(await input.inputValue(), '7');
  assert.match(await status(), /Updates every 7 s/);
  assert.equal(await page.evaluate(key => localStorage.getItem(key), key), '7');
  await noRequest(6999);
  await requestAfter(1);
  await input.press('Escape');
  assert.equal(await input.inputValue(), '7');

  await page.reload();
  await settled();
  assert.equal(await input.inputValue(), '7');
  await noRequest(6999);
  await requestAfter(1);

  await page.locator('#pause').click();
  await enter('3');
  assert.match(await status(), /Page updates paused · Every 3 s/);
  await noRequest(6000);
  for (const [id, param, value] of [['route', 'route', 'upstream'], ['protocol', 'protocol', 'http_connect']]) {
    const filtered = snapshot();
    await page.locator('#' + id).selectOption(value);
    assert.equal(new URL((await filtered).url()).searchParams.get(param), value);
    await settled();
  }
  const resumed = snapshot();
  await page.locator('#pause').click();
  await resumed;
  await settled();
  await noRequest(2999);
  await requestAfter(1);

  const setHidden = hidden => page.evaluate(hidden => {
    Object.defineProperty(document, 'hidden', {configurable: true, value: hidden});
    document.dispatchEvent(new Event('visibilitychange'));
  }, hidden);
  await setHidden(true);
  await noRequest(6000);
  const visible = snapshot();
  await setHidden(false);
  await visible;
  await settled();
  await noRequest(2999);
  await requestAfter(1);

  await enter('1');
  const release = holdNext();
  const pending = snapshot();
  await page.clock.runFor(1000);
  await page.locator('#connections[aria-busy="true"]').waitFor({state: 'attached'});
  await noRequest(2000);
  release();
  await pending;
  await settled();
  await noRequest(999);
  await requestAfter(1);

  failNext();
  await requestAfter(1000, 503);
  assert.match(await status(), /Updates interrupted/);
  await noRequest(1999);
  await requestAfter(1);
  await enter('5');
  failNext();
  await requestAfter(5000, 503);
  await noRequest(4999);
  await requestAfter(1);

  await enter('9007199254740993');
  assert.equal(await page.evaluate(key => localStorage.getItem(key), key), '9007199254740993');
  await enter('2147484');
  await noRequest(2147483647, ms => page.clock.fastForward(ms));
  await noRequest(352);
  await requestAfter(1);

  await page.evaluate(key => localStorage.setItem(key, '0'), key);
  await page.reload();
  await settled();
  assert.equal(await input.inputValue(), '1');
  await page.addInitScript(() => {
    for (const method of ['getItem', 'setItem']) {
      Storage.prototype[method] = () => { throw new DOMException('Storage disabled', 'SecurityError'); };
    }
  });
  await page.reload();
  await settled();
  assert.equal(await input.inputValue(), '1');
  await enter('4');
  await noRequest(3999);
  await requestAfter(1);
}

async function testTrafficChart(page, setSampleTime) {
  const start = Date.parse('2026-01-01T12:00:00Z');
  const range = minutes => page.getByRole('button', {name: minutes + ' min', exact: true});
  const snapshot = () => page.waitForResponse(response => new URL(response.url()).pathname === '/api/snapshot');
  const settled = () => page.locator('#connections[aria-busy="false"]').waitFor({state: 'attached'});
  const chartState = () => page.evaluate(() => {
    const chart = window.testTrafficChart;
    return {
      times: chart.data[0],
      upload: chart.data[1],
      colors: chart.series.slice(1).map(series => series.stroke(chart, 0)),
      min: chart.scales.x.min,
      max: chart.scales.x.max,
      width: chart.width
    };
  });
  const axisLabels = values => page.evaluate(values => {
    const chart = window.testTrafficChart;
    return chart.axes[1].values(chart, values);
  }, values);
  const hoverAt = async time => {
    const over = page.locator('#traffic-plot .u-over');
    await over.scrollIntoViewIfNeeded();
    const bounds = await over.boundingBox();
    const left = await page.evaluate(time => window.testTrafficChart.valToPos(time / 1000, 'x'), time);
    await page.mouse.move(bounds.x + left, bounds.y + bounds.height / 2);
    await page.clock.runFor(32);
  };

  assert.equal(await page.locator('#traffic-empty').isVisible(), true);
  assert.equal(await range(1).getAttribute('aria-pressed'), 'true');
  setSampleTime(start);
  const initial = snapshot();
  await page.locator('#route').selectOption('upstream');
  assert.equal(new URL((await initial).url()).searchParams.get('traffic'), '1');
  await settled();
  for (const minutes of [1, 5, 10]) {
    await range(minutes).click();
    const chart = await chartState();
    assert.equal(await range(minutes).getAttribute('aria-pressed'), 'true');
    assert.equal(chart.min, start / 1000 - minutes * 60);
    assert.equal(chart.max, start / 1000);
    assert.equal(chart.upload.filter(value => value != null).length, minutes * 60 - 5);
    assert.equal(chart.upload.includes(null), true);
    assert.equal(chart.upload.includes(0), true);
    assert.deepEqual(chart.colors, ['#2466db', '#158169']);
  }
  await range(1).click();
  await page.locator('#units').selectOption('MiB');
  assert.deepEqual(await axisLabels([1000000]), ['0.954 MiB/s']);
  await page.locator('#units').selectOption('Auto');
  assert.deepEqual(
    await axisLabels([0, 512, 1024, 1000000, 1048576, 1073741824]),
    ['0 B/s', '512 B/s', '1 KiB/s', '977 KiB/s', '1 MiB/s', '1 GiB/s']
  );
  await hoverAt(start - 10000);
  assert.equal(await page.locator('#traffic-tooltip').isVisible(), true);
  assert.equal(await page.locator('#traffic-tooltip-time').getAttribute('datetime'),
    new Date(start - 10000).toISOString());
  assert.equal(await page.locator('#traffic-tooltip-up').textContent(), '1.00 KiB/s');
  assert.equal(await page.locator('#traffic-tooltip-down').textContent(), '2.00 KiB/s');
  assert.equal(await page.locator('#traffic-tooltip .up').evaluate(el => getComputedStyle(el).color), 'rgb(36, 102, 219)');
  assert.equal(await page.locator('#traffic-tooltip .down').evaluate(el => getComputedStyle(el).color), 'rgb(21, 129, 105)');
  await page.locator('#units').selectOption('B');
  await hoverAt(start - 10000);
  assert.equal(await page.locator('#traffic-tooltip-up').textContent(), '1024 B/s');
  await hoverAt(start - 22000);
  assert.equal(await page.locator('#traffic-tooltip').isVisible(), false);
  const plot = page.locator('#traffic-plot');
  assert.equal((await chartState()).width, await plot.evaluate(el => el.clientWidth));
  assert.ok((await plot.boundingBox()).width < 520);

  const input = page.locator('#refresh-seconds');
  await input.fill('30');
  await input.press('Enter');
  await hoverAt(start - 10000);
  setSampleTime(start + 30000);
  await page.clock.runFor(29967);
  assert.equal((await chartState()).max, start / 1000);
  const refreshed = snapshot();
  await page.clock.runFor(1);
  await refreshed;
  await settled();
  const chart = await chartState();
  assert.equal(chart.max, start / 1000 + 30);
  assert.equal(chart.times.filter(time => time > start / 1000).length, 30);
  assert.equal(await page.locator('#traffic-tooltip').isVisible(), true);
  assert.equal(await page.locator('#traffic-tooltip-time').getAttribute('datetime'),
    new Date(start + 20000).toISOString());

  await page.locator('#pause').click();
  setSampleTime(start + 60000);
  const manual = snapshot();
  await page.locator('#route').selectOption('direct');
  assert.equal(new URL((await manual).url()).searchParams.has('traffic'), false);
  await settled();
  await page.clock.runFor(30000);
  await range(5).click();
  assert.equal((await chartState()).max, start / 1000 + 30);
  const resumed = snapshot();
  await page.locator('#pause').click();
  await resumed;
  await settled();
  assert.equal((await chartState()).max, start / 1000 + 60);
}

(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE?{executablePath:process.env.CHROME_EXECUTABLE}:{})});
 try{
  const page=await browser.newPage({viewport:{width:1560,height:1050}}),errors=[],requests=[];
  page.on('pageerror',e=>errors.push(e.message));
  let failNext=false,delayPid=false,heldSnapshot,trafficSampleTime;
  const data=Array.from({length:203},(_,i)=>({id:''+(i+1),clientId:''+(i+1),source:{host:i===0?'2001:db8:abcd:abcd::1234':'127.0.0.1',port:50000+i},listener:{host:'127.0.0.1',port:8380},process:{pid:i+1,name:i===202?'extremely-long-process-name-for-ellipsis-verification.exe':'process-'+(i+1)+'.exe',status:'resolved'},targetHost:i===202?'very-long-target-hostname-for-ellipsis.example.com':'api.example.com',targetPort:443,protocol:'http_connect',route:'upstream',upstreamHost:'proxy.example.com',upstreamPort:443,state:'active',startedAt:Date.now()+i,uploadBytes:1048576,downloadBytes:2097152,uploadBps:i+1,downloadBps:2*(i+1)}));
  await page.route('http://monitor.test/**',async route=>{
   const url=new URL(route.request().url());
   if(url.pathname==='/api/snapshot'){
    const q=Object.fromEntries(url.searchParams);requests.push(q);
    if(failNext){failNext=false;return route.fulfill({status:503,body:''});}
    const hold=heldSnapshot;heldSnapshot=undefined;
    if(hold)await hold;
    if(delayPid&&q.sort==='pid')await new Promise(r=>setTimeout(r,400));
    let records=[...data];if(q.q)records=records.filter(r=>r.process.name.includes(q.q));
    records.sort((a,b)=>(q.sort==='pid'?a.process.pid-b.process.pid:q.sort==='uploadBps'?a.uploadBps-b.uploadBps:b.startedAt-a.startedAt)*(q.sort&&q.order==='desc'?-1:1));
    const size=Number(q.pageSize),pages=Math.max(1,Math.ceil(records.length/size)),number=Math.min(Number(q.page),pages);
    const trafficHistory = trafficSampleTime == null ? [] : Array.from({length: 600}, (_, i) => {
      const sampleTime = trafficSampleTime - (599 - i) * 1000;
      return {sampleTime, uploadBps: i % 30 ? 1024 : 0, downloadBps: 2048};
    }).filter(point => {
      const gapStart = Date.parse('2026-01-01T11:59:36Z');
      return point.sampleTime < gapStart || point.sampleTime >= gapStart + 5000;
    });
    return route.fulfill({contentType:'application/json',body:JSON.stringify({serverPid:43210,sampleTime:trafficSampleTime??Date.now(),httpPort:8380,socksPort:1080,upstream:{host:'proxy.example.com',port:443},processStatus:'ready',summary:{activeClients:203,activeFlows:203,directFlows:0,upstreamFlows:203,uploadBytes:1048576,downloadBytes:2097152,uploadBps:1024,downloadBps:2048},pagination:{page:number,pageSize:size,total:records.length,pages},connections:records.slice((number-1)*size,number*size),...(q.traffic==='1'?{trafficHistory}:{})})});
   }
   const name=url.pathname==='/'?'index.html':url.pathname.slice(1);
   let body=fs.readFileSync(path.join(resources,name),'utf8');
   if(name.endsWith('/uPlot.iife.min.js')) {
     body+='\nuPlot = new Proxy(uPlot, {construct(target, args) {return window.testTrafficChart = Reflect.construct(target, args);}});';
   }
   return route.fulfill({contentType:name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'text/html',headers:{'Content-Security-Policy':"default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'"},body});
  });
  await page.goto('http://monitor.test/');await page.locator('#rows tr').first().waitFor();
  assert.equal(await page.locator('#ports').textContent(),'PID 43210 / HTTP :8380 / SOCKS :1080');
  assert.equal(await page.locator('thead th').count(),9);assert.equal(await page.locator('#rows tr').count(),50);
  assert.equal(await page.locator('th').nth(0).innerText(),'Process Name');assert.equal(await page.locator('th').nth(1).innerText(),'PID');
  const widths=()=>page.locator('#columns col').evaluateAll(cols=>cols.map(c=>parseFloat(c.style.width)));
  const defaults=await widths();assert.equal(defaults[0],180);assert.equal(defaults[1],80);
  await page.locator('#pause').click();
  for(const [key,first] of [['processName','ascending'],['pid','ascending'],['sourceIp','ascending'],['protocol','ascending'],['target','ascending'],['route','ascending'],['uploadBps','descending'],['downloadBps','descending'],['status','ascending']]){
   const heading=page.locator(`th[data-sort="${key}"]`);await heading.locator('button').click();await page.waitForFunction(()=>document.querySelector('#connections').getAttribute('aria-busy')==='false');
   assert.equal(await heading.getAttribute('aria-sort'),first);assert.equal(requests.at(-1).sort,key);
   await heading.locator('button').press('Enter');await page.waitForFunction(()=>document.querySelector('#connections').getAttribute('aria-busy')==='false');
   assert.equal(await heading.getAttribute('aria-sort'),first==='ascending'?'descending':'ascending');
  }
  const drag=async(index,delta)=>{const handle=page.locator('th .resize-handle').nth(index),box=await handle.boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width/2+delta,box.y+box.height/2,{steps:5});await page.mouse.up();};
  for(let i=0;i<9;i++){
   await page.locator('th .resize-handle').nth(i).scrollIntoViewIfNeeded();const before=await widths(),beforeRequests=requests.length;await drag(i,-10);const after=await widths();assert.equal(after[i],before[i]-10);assert.equal(requests.length,beforeRequests);
   for(let j=0;j<9;j++)if(i!==j)assert.equal(after[j],before[j]);
  }
  await page.locator('.table-wrap').evaluate(n=>n.scrollLeft=0);await drag(0,-500);assert.equal((await widths())[0],48);
  const content=page.locator('#rows tr').first().locator('td').first().locator('.cell-text');assert.equal(await content.evaluate(n=>getComputedStyle(n).textOverflow),'ellipsis');assert(await content.evaluate(n=>n.scrollWidth>n.clientWidth));assert((await content.getAttribute('title')).length>10);
  const resized=await widths();
  for(const size of ['25','50','100','200']){await page.locator('#page-size').selectOption(size);await page.waitForFunction(n=>document.querySelectorAll('#rows tr').length===n,Number(size));assert.equal(requests.at(-1).page,'1');assert.equal(requests.at(-1).pageSize,size);assert.deepEqual(await widths(),resized);}
  await page.locator('#next').click();await page.waitForFunction(()=>document.querySelectorAll('#rows tr').length===3);assert.match(await page.locator('#page').textContent(),/2 \/ 2/);assert.equal(await page.locator('#next').isDisabled(),true);
  await page.locator('#page-size').selectOption('25');await page.waitForFunction(()=>document.querySelectorAll('#rows tr').length===25);assert.match(await page.locator('#page').textContent(),/1 \/ 9/);
  await page.locator('#rows tr').first().click();await page.locator('#units').selectOption('MiB');assert.deepEqual(await widths(),resized);assert.match(await page.locator('#detail').textContent(),/MiB/);
  await page.locator('#search').fill('process-1.');await page.waitForFunction(()=>document.querySelectorAll('#rows tr').length===1);assert.equal(requests.at(-1).pageSize,'25');assert.deepEqual(await widths(),resized);
  await page.locator('#search').fill('');await page.waitForFunction(()=>document.querySelectorAll('#rows tr').length===25);
  delayPid=true;await page.locator('th[data-sort="pid"] button').click();await page.locator('th[data-sort="uploadBps"] button').click();await page.waitForFunction(()=>document.querySelector('#connections').getAttribute('aria-busy')==='false');await page.waitForTimeout(500);assert.equal(await page.locator('th[data-sort="uploadBps"]').getAttribute('aria-sort'),'descending');assert.equal(await page.locator('#rows tr').first().getAttribute('data-id'),'203');delayPid=false;
  failNext=true;await page.locator('#page-size').selectOption('100');await page.waitForFunction(()=>document.querySelector('#refresh').textContent.includes('Updates interrupted'));assert.equal(await page.locator('#page-size').inputValue(),'25');assert.deepEqual(await widths(),resized);
  failNext=true;await page.locator('th[data-sort="pid"] button').click();await page.waitForFunction(()=>document.querySelector('#connections').getAttribute('aria-busy')==='false');assert.equal(await page.locator('th[data-sort="uploadBps"]').getAttribute('aria-sort'),'descending');assert.equal(await page.locator('#rows tr').count(),25);
  await page.locator('#pause').click();await page.waitForTimeout(1300);assert.deepEqual(await widths(),resized);
  await page.reload();await page.locator('#rows tr').first().waitFor();assert.deepEqual(await widths(),defaults);assert.equal(await page.locator('#page-size').inputValue(),'50');assert.equal(await page.locator('#units').inputValue(),'MiB');
  await page.locator('#units').selectOption('Auto');await page.screenshot({path:path.resolve(__dirname,'../../../target/monitor-table.png'),fullPage:true});
  await page.setViewportSize({width:520,height:900});assert(await page.locator('.table-wrap').evaluate(n=>n.scrollWidth>n.clientWidth));const footer=await page.locator('footer').boundingBox();assert(footer.width<=520);assert(!/[\u4e00-\u9fff]/.test(await page.locator('body').textContent()));
  console.log('PASS: 9 sortable columns, keyboard, all resize handles, ellipsis, full paging choices, paused actions, stale responses, rollback, refresh reset, units, narrow layout.');
  await testRefreshControl(page, {
    failNext: () => { failNext = true; },
    holdNext: () => {
      let release;
      heldSnapshot = new Promise(resolve => { release = resolve; });
      return release;
    }
  });
  await testTrafficChart(page, time => { trafficSampleTime = time; });
  assert.deepEqual(errors, []);
  console.log('PASS: refresh presets, keyboard and direct entry, validation, persistence, request timing, pause, visibility, slow requests, failure backoff, long delays.');
  console.log('PASS: traffic ranges, colors, sampled hover values, units, gaps, narrow layout, 30 s refresh and pause/resume.');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1});
