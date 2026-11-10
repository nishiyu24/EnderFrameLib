// Chrome を CDP で駆動し、rAF ごとに 1 変更を起こして、フレーム全段の CPU 時間をトレースで集計する
const http=require('http'),fs=require('fs'),path=require('path'),cp=require('child_process'),os=require('os');
const [,, doc, scen, gpuMode] = process.argv;   // gpuMode: soft | gpu
const CHROME='C:/Program Files/Google/Chrome/Application/chrome.exe';
function largeList(n){let s='<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { padding: 4px; border-bottom: 1px solid #ccc; } li.fh { background: #eef; }</style></head><body><div id="scroller" style="height: 400px; overflow-y: auto;"><ul>';
 for(let i=1;i<=n;i++) s+='<li id="item-'+i+'">Row item #'+i+' with description text</li>'; return s+'</ul></div></body></html>';}
const SCRIPT=`<script>
const DOC=${JSON.stringify(doc)}, SC=${JSON.stringify(scen)};
let frames=0;window.__frames=0;
function init(){
 const d=document; const list=DOC.startsWith('large-list');
 const hv=list?[d.getElementById('item-100'),d.getElementById('item-101')]:[...d.querySelectorAll('.btn')].slice(0,2);
 const textNode=list?d.getElementById('item-250').firstChild:[...d.querySelectorAll('span')].find(s=>s.firstChild&&s.firstChild.nodeType===3&&s.textContent.trim().length>3).firstChild;
 const styleEl=list?d.getElementById('item-100'):d.querySelector('.btn');
 const ul=list?d.querySelector('ul'):d.querySelector('div');
 const sc=list?d.getElementById('scroller'):d.scrollingElement;
 const M={idle:i=>{},
  hover:i=>{hv[i&1].classList.add('fh');hv[(i+1)&1].classList.remove('fh');},
  text:i=>{textNode.data=(i&1)?'Row item A with text '+(i&7):'Row item B with text '+(i&7);},
  style:i=>{styleEl.style.color=(i&1)?'#112233':'#223344';},
  inherited:i=>{d.body.style.color=(i&1)?'#112233':'#223344';},
  fontsize:i=>{d.body.style.fontSize=(i&1)?'16px':'15px';},
  child:i=>{if(i&1)ul.removeChild(ul.lastChild);else{const li=d.createElement('li');li.textContent='inserted '+i;ul.appendChild(li);}},
  scroll:i=>{sc.scrollTop+=(i&1)?1:-1;}};
 const f=M[SC];
 (function loop(){ f(frames++); window.__frames=frames; requestAnimationFrame(loop); })();
}
addEventListener('load',()=>setTimeout(init,300));
</script>`;
const srv=http.createServer((req,res)=>{
  let html = doc.startsWith('large-list') ? largeList(parseInt(doc.split('-')[2]||'500')) : fs.readFileSync(path.join(__dirname,'../../../src/main/resources/data/enderframelib/enderframe_ui/showcase.html'),'utf8').replace(/:hover/g,'.fh');
  html=html+SCRIPT; res.setHeader('Content-Type','text/html'); res.end(html);
}).listen(0,async()=>{
  const port=srv.address().port, dbg=9300+Math.floor(Math.random()*500);
  const prof=fs.mkdtempSync(path.join(os.tmpdir(),'prof-'));
  const args=['--headless=new','--no-first-run','--user-data-dir='+prof,'--window-size=800,600','--remote-debugging-port='+dbg,'--remote-allow-origins=*'];
  if(gpuMode==='soft') args.push('--disable-gpu');
  args.push('http://127.0.0.1:'+port+'/');
  const child=cp.spawn(CHROME,args,{stdio:'ignore'});
  const sleep=ms=>new Promise(r=>setTimeout(r,ms));
  let tabs; for(let i=0;i<50;i++){try{tabs=await (await fetch(`http://127.0.0.1:${dbg}/json`)).json(); if(tabs.find(t=>t.type==='page'))break;}catch(e){} await sleep(200);}
  const tab=tabs.find(t=>t.type==='page'); const ws=new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise(r=>ws.onopen=r);
  let id=0; const pend=new Map(); const events=[];
  ws.onmessage=m=>{const o=JSON.parse(m.data); if(o.id&&pend.has(o.id)){pend.get(o.id)(o.result);pend.delete(o.id);} else if(o.method==='Tracing.dataCollected'){for(const e of o.params.value)events.push(e);} else if(o.method==='Tracing.tracingComplete'){done&&done();}};
  let done; const send=(method,params={})=>new Promise(r=>{const i=++id;pend.set(i,r);ws.send(JSON.stringify({id:i,method,params}));});
  const ev=async e=>(await send('Runtime.evaluate',{expression:e,returnByValue:true})).result.value;
  for(let i=0;i<100;i++){ const v=await ev('window.__frames||0'); if(i%20==0)console.error('frames',v,tab.url); if(v>=100)break; await sleep(200);}
  await sleep(500);
  await send('Tracing.start',{categories:'devtools.timeline,disabled-by-default-devtools.timeline,cc,viz,benchmark,gpu',transferMode:'ReportEvents'});
  console.error('dbg',JSON.stringify(await send('Runtime.evaluate',{expression:'(()=>{try{return typeof init+" "+document.readyState+" "+document.querySelectorAll(".btn").length}catch(e){return String(e)}})()',returnByValue:true})));
  const f0=await ev('window.__frames'); await sleep(3000); const f1=await ev('window.__frames');
  const fin=new Promise(r=>done=r); await send('Tracing.end'); await fin;
  const frames=f1-f0;
  // 区間 'X'(完了イベント) の名前別合計。入れ子は無視して名前別の自己 + 子の合計をそのまま報告
  const agg={}; for(const e of events){ if(e.ph==='X'&&e.dur){ (agg[e.name]=agg[e.name]||{us:0,n:0}); agg[e.name].us+=e.dur; agg[e.name].n++; } }
  const pick=['UpdateLayoutTree','Layout','PrePaint','Paint','Layerize','Commit','CompositeLayers','RasterTask','ImageDecodeTask','Decode Image','GPUTask','FunctionCall','FireAnimationFrame','HitTest','UpdateLayer','BeginMainThreadFrame','ProxyMain::BeginMainFrame','LayerTreeHostImpl::PrepareToDraw','DrawFrame','Display::DrawAndSwap'];
  const out={doc,scen,gpuMode,frames,perFrameMs:{}};
  for(const k of pick) if(agg[k]) out.perFrameMs[k]={ms:+(agg[k].us/1000/frames).toFixed(4),callsPerFrame:+(agg[k].n/frames).toFixed(2)};
  console.log(JSON.stringify(out));
  ws.close(); child.kill(); srv.close(); process.exit(0);
});
setTimeout(()=>{console.log('TIMEOUT');process.exit(2)},120000);
