const L=require("fs").readFileSync(process.argv[2]||"trace.log","utf8").split("\n").filter(l=>l.startsWith("{")).map(JSON.parse);
const g=(o,k)=>o.perFrameMs[k]?o.perFrameMs[k].ms:0;
console.log("mode doc scen | main(BeginMainFrame) = style+layout+prepaint+paint+layerize+commit | raster(worker CPU) | draw | total CPU");
for(const o of L){const ms=g(o,"ProxyMain::BeginMainFrame");const st=g(o,"UpdateLayoutTree"),ly=g(o,"Layout"),pp=g(o,"PrePaint"),pa=g(o,"Paint"),lz=g(o,"Layerize"),cm=g(o,"Commit"),ra=g(o,"RasterTask"),dr=g(o,"Display::DrawAndSwap");
 console.log(`${o.gpuMode.padEnd(4)} ${o.doc.padEnd(10)} ${o.scen.padEnd(9)} main=${ms.toFixed(3)} (style ${st.toFixed(3)} layout ${ly.toFixed(3)} prepaint ${pp.toFixed(3)} paint ${pa.toFixed(3)} layerize ${lz.toFixed(3)} commit ${cm.toFixed(3)}) raster=${ra.toFixed(3)} draw=${dr.toFixed(3)} total=${(ms+ra+dr).toFixed(3)} frames=${o.frames}`);}
