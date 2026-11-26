// Chrome の「入力 → 表示」の遅延（B1③）。CDP で本物のマウス移動を送り、トレースの入力遅延イベントと、ページ内の rAF 刻みで測る。
//   node latency.js [soft|gpu] [N=60]  → 1 行の JSON（フレーム数 = 遅延 ÷ 16.67 ms（60Hz 描画）ではなく、入力 → 最初に表示されるまでに跨いだ vsync の数）
// 方式: ページは mousemove で class を切り替える（見た目が変わる）。入力を送った時刻と、変更後の最初の rAF の時刻・何フレーム目かを記録。
//       あわせて Tracing の InputLatency::MouseMove（ブラウザが入力を受けてからフレームが提示されるまで）の区間も集計する。
const http=require('http'),fs=require('fs'),path=require('path'),cp=require('child_process'),os=require('os');
const [,, gpuMode='gpu', nArg='60'] = process.argv; const N=parseInt(nArg);
const CHROME='C:/Program Files/Google/Chrome/Application/chrome.exe';
const HTML=fs.readFileSync(path.join(__dirname,'../../../mod/src/main/resources/data/enderframelib/enderframe_ui/showcase.html'),'utf8').replace(/:hover/g,'.fh')+`<script>
window.__lat=[];let frame=0,pending=null;
(function loop(){frame++;requestAnimationFrame(loop);})();
const b=[...document.querySelectorAll('.btn')].slice(0,2);
addEventListener('mousemove',e=>{const t0=e.timeStamp;const f0=frame;const i=window.__lat.length&1;
  b[i].classList.add('fh');b[1-i].classList.remove('fh');
  requestAnimationFrame(t=>{window.__lat.push({inputToRafMs:t-t0,framesAfter:frame-f0});});});
</script>`;
const srv=http.createServer((q,r)=>{r.setHeader('Content-Type','text/html');r.end(HTML);}).listen(0,async()=>{
  const port=srv.address().port,dbg=9300+Math.floor(Math.random()*500);
  const prof=fs.mkdtempSync(path.join(os.tmpdir(),'lat-'));
  const args=['--headless=new','--no-first-run','--user-data-dir='+prof,'--window-size=800,600','--remote-debugging-port='+dbg,'--remote-allow-origins=*'];
  if(gpuMode==='soft')args.push('--disable-gpu'); args.push('http://127.0.0.1:'+port+'/');
  const child=cp.spawn(CHROME,args,{stdio:'ignore'}); const sleep=ms=>new Promise(r=>setTimeout(r,ms));
  let tabs;for(let i=0;i<50;i++){try{tabs=await(await fetch(`http://127.0.0.1:${dbg}/json`)).json();if(tabs.find(t=>t.type==='page'))break;}catch(e){}await sleep(200);}
  const tab=tabs.find(t=>t.type==='page');const ws=new WebSocket(tab.webSocketDebuggerUrl);await new Promise(r=>ws.onopen=r);
  let id=0;const pend=new Map();const events=[];let done;
  ws.onmessage=m=>{const o=JSON.parse(m.data);if(o.id&&pend.has(o.id)){pend.get(o.id)(o.result);pend.delete(o.id);}else if(o.method==='Tracing.dataCollected'){for(const e of o.params.value)events.push(e);}else if(o.method==='Tracing.tracingComplete'){done&&done();}};
  const send=(method,params={})=>new Promise(r=>{const i=++id;pend.set(i,r);ws.send(JSON.stringify({id:i,method,params}));});
  const ev=async e=>(await send('Runtime.evaluate',{expression:e,returnByValue:true})).result.value;
  await sleep(2000);
  await send('Tracing.start',{categories:'benchmark,input,latencyInfo,disabled-by-default-latency_info,cc,viz',transferMode:'ReportEvents'});
  for(let i=0;i<N;i++){await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:100+(i%2)*40,y:100+(i%3)*10});await sleep(70);}
  await sleep(300);
  const fin=new Promise(r=>done=r);await send('Tracing.end');await fin;
  const lat=await ev('JSON.stringify(window.__lat)');const L=JSON.parse(lat||'[]');
  const med=a=>{a=a.slice().sort((x,y)=>x-y);return a.length?a[a.length>>1]:null;};
  const fr=L.map(x=>x.framesAfter),ms=L.map(x=>x.inputToRafMs);
  // トレース: 入力イベントの遅延区間（b/e の対）
  const names={};for(const e of events){if(/Latency|latency/.test(e.name))names[e.name]=(names[e.name]||0)+1;}
  const open=new Map(),durs=[];for(const e of events){if(e.name==='InputLatency::MouseMove'||e.name==='InputLatency::MouseMove '){if(e.ph==='b')open.set(e.id2?JSON.stringify(e.id2):e.id,e.ts);else if(e.ph==='e'){const k=e.id2?JSON.stringify(e.id2):e.id;if(open.has(k)){durs.push((e.ts-open.get(k))/1000);open.delete(k);}}}}
  console.log(JSON.stringify({gpuMode,samples:L.length,framesBetweenInputAndRafMedian:med(fr),framesMax:fr.length?Math.max(...fr):null,inputToRafMsMedian:med(ms),traceInputLatencyMsMedian:med(durs),traceSamples:durs.length,latencyEventNames:names}));
  ws.close();child.kill();srv.close();process.exit(0);
});
setTimeout(()=>{console.log('TIMEOUT');process.exit(2)},120000);
