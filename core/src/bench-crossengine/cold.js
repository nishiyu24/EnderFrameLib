// Chrome のコールド起動（B1①）: プロセス起動 → 初回描画までの壁時計。新しいプロファイルで Chrome を起動し、ページが
// load 後に rAF を 2 回回した（= 初回フレームが提示された）時点で POST を送る。node がその到着までを測る。
//   node cold.js <showcase|large-list-2000> [runs=5] [soft|gpu]  → 各回の ms と中央値
const http=require('http'),fs=require('fs'),path=require('path'),cp=require('child_process'),os=require('os');
const [,, doc='showcase', runsArg='5', gpuMode='gpu']=process.argv; const RUNS=parseInt(runsArg);
const CHROME='C:/Program Files/Google/Chrome/Application/chrome.exe';
function largeList(n){let s='<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { padding: 4px; border-bottom: 1px solid #ccc; }</style></head><body><div id="scroller" style="height: 400px; overflow-y: auto;"><ul>';for(let i=1;i<=n;i++)s+='<li id="item-'+i+'">Row item #'+i+' with description text</li>';return s+'</ul></div></body></html>';}
const BEACON='<script>addEventListener("load",()=>requestAnimationFrame(()=>requestAnimationFrame(()=>fetch("/done",{method:"POST",body:"x"}))));</script>';
const html=(doc.startsWith('large-list')?largeList(parseInt(doc.split('-')[2]||'500')):fs.readFileSync(path.join(__dirname,'../../../src/main/resources/data/enderframelib/enderframe_ui/showcase.html'),'utf8'))+BEACON;
async function once(){
  return new Promise(resolve=>{
    let t0,child;
    const srv=http.createServer((q,r)=>{
      if(q.method==='POST'){const ms=Number(process.hrtime.bigint()-t0)/1e6;r.end('ok');setTimeout(()=>{child.kill();srv.close();resolve(ms);},100);return;}
      r.setHeader('Content-Type','text/html');r.end(html);
    }).listen(0,()=>{
      const prof=fs.mkdtempSync(path.join(os.tmpdir(),'cold-'));
      const args=['--headless=new','--no-first-run','--no-default-browser-check','--user-data-dir='+prof,'--window-size=800,600'];
      if(gpuMode==='soft')args.push('--disable-gpu'); args.push('http://127.0.0.1:'+srv.address().port+'/');
      t0=process.hrtime.bigint(); child=cp.spawn(CHROME,args,{stdio:'ignore'});
    });
  });
}
(async()=>{const r=[];for(let i=0;i<RUNS;i++){r.push(await once());await new Promise(s=>setTimeout(s,800));}
 const s=r.slice().sort((a,b)=>a-b);console.log(JSON.stringify({doc,gpuMode,runsMs:r.map(x=>+x.toFixed(0)),medianMs:+s[s.length>>1].toFixed(0)}));process.exit(0);})();
setTimeout(()=>{console.log('TIMEOUT');process.exit(2)},240000);
