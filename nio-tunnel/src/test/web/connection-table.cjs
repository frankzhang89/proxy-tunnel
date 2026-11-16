// Run with Playwright available through NODE_PATH (or PLAYWRIGHT_MODULE).
// This uses intercepted fixture data; Java tests cover server sorting and pagination.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const resources=path.resolve(__dirname,'../../main/resources/web');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE?{executablePath:process.env.CHROME_EXECUTABLE}:{})});
 try{
  const page=await browser.newPage({viewport:{width:1560,height:1050}}),errors=[],requests=[];
  page.on('pageerror',e=>errors.push(e.message));
  let failNext=false,delayPid=false;
  const data=Array.from({length:203},(_,i)=>({id:''+(i+1),clientId:''+(i+1),source:{host:i===0?'2001:db8:abcd:abcd::1234':'127.0.0.1',port:50000+i},listener:{host:'127.0.0.1',port:8380},process:{pid:i+1,name:i===202?'extremely-long-process-name-for-ellipsis-verification.exe':'process-'+(i+1)+'.exe',status:'resolved'},targetHost:i===202?'very-long-target-hostname-for-ellipsis.example.com':'api.example.com',targetPort:443,protocol:'http_connect',route:'upstream',upstreamHost:'proxy.example.com',upstreamPort:443,state:'active',startedAt:Date.now()+i,uploadBytes:1048576,downloadBytes:2097152,uploadBps:i+1,downloadBps:2*(i+1)}));
  await page.route('http://monitor.test/**',async route=>{
   const url=new URL(route.request().url());
   if(url.pathname==='/api/snapshot'){
    const q=Object.fromEntries(url.searchParams);requests.push(q);
    if(failNext){failNext=false;return route.fulfill({status:503,body:''});}
    if(delayPid&&q.sort==='pid')await new Promise(r=>setTimeout(r,400));
    let records=[...data];if(q.q)records=records.filter(r=>r.process.name.includes(q.q));
    records.sort((a,b)=>(q.sort==='pid'?a.process.pid-b.process.pid:q.sort==='uploadBps'?a.uploadBps-b.uploadBps:b.startedAt-a.startedAt)*(q.sort&&q.order==='desc'?-1:1));
    const size=Number(q.pageSize),pages=Math.max(1,Math.ceil(records.length/size)),number=Math.min(Number(q.page),pages);
    return route.fulfill({contentType:'application/json',body:JSON.stringify({serverPid:43210,sampleTime:Date.now(),httpPort:8380,socksPort:1080,upstream:{host:'proxy.example.com',port:443},processStatus:'ready',summary:{activeClients:203,activeFlows:203,directFlows:0,upstreamFlows:203,uploadBytes:1048576,downloadBytes:2097152,uploadBps:1024,downloadBps:2048},pagination:{page:number,pageSize:size,total:records.length,pages},connections:records.slice((number-1)*size,number*size)})});
   }
   const name=url.pathname==='/'?'index.html':url.pathname.slice(1);
   return route.fulfill({contentType:name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'text/html',body:fs.readFileSync(path.join(resources,name))});
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
  await page.setViewportSize({width:520,height:900});assert(await page.locator('.table-wrap').evaluate(n=>n.scrollWidth>n.clientWidth));const footer=await page.locator('footer').boundingBox();assert(footer.width<=520);assert(!/[\u4e00-\u9fff]/.test(await page.locator('body').textContent()));assert.deepEqual(errors,[]);
  console.log('PASS: 9 sortable columns, keyboard, all resize handles, ellipsis, full paging choices, paused actions, stale responses, rollback, refresh reset, units, narrow layout.');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1});
