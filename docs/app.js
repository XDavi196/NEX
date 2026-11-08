(function(){
  "use strict";
  const API="https://api.github.com/repos/XDavi196/NEX/releases?per_page=8";
  const RELEASES="https://github.com/XDavi196/NEX/releases";
  const $=id=>document.getElementById(id);
  $("year").textContent=new Date().getFullYear();
  function link(label,url){
    const a=document.createElement("a");a.className="asset";a.textContent=label;
    try{const u=new URL(url);a.href=u.protocol==="https:"&&u.hostname==="github.com"?u.href:RELEASES}catch(_){a.href=RELEASES}
    a.target="_blank";a.rel="noopener noreferrer";return a;
  }
  function date(value){const d=new Date(value);return Number.isNaN(d.getTime())?"Data indisponível":new Intl.DateTimeFormat("pt-BR",{day:"2-digit",month:"short",year:"numeric"}).format(d)}
  function size(value){return Number.isFinite(value)?(value/1048576).toFixed(1).replace(".",",")+" MB":"Tamanho indisponível"}
  function row(item){
    const article=document.createElement("article");article.className="release-row";
    const main=document.createElement("div");main.className="release-row-main";
    const icon=document.createElement("span");icon.className="release-icon";icon.textContent="↓";
    const details=document.createElement("div");const title=document.createElement("h4");title.textContent=item.name||item.tag_name||"NEX release";
    const meta=document.createElement("p");meta.textContent=date(item.published_at)+(item.prerelease?" · Experimental":" · Estável");
    details.append(title,meta);main.append(icon,details);
    const a=document.createElement("a");a.href=item.html_url||RELEASES;a.target="_blank";a.rel="noopener";a.innerHTML="Notas e arquivos <b>↗</b>";
    article.append(main,a);return article;
  }
  async function load(){
    const list=$("release-list");
    try{
      const response=await fetch(API,{headers:{Accept:"application/vnd.github+json"},cache:"no-store"});
      if(!response.ok)throw new Error("GitHub API "+response.status);
      const items=(await response.json()).filter(x=>!x.draft&&x.html_url);
      if(!items.length)throw new Error("Nenhuma release publicada");
      const latest=items[0],assets=latest.assets||[];
      const apk=assets.find(x=>(x.name||"").toLowerCase().endsWith(".apk"));
      const checksum=assets.find(x=>/sha256|checksum/i.test(x.name||""));
      $("hero-version").textContent=latest.tag_name||"Última versão";
      $("hero-download").href=apk?apk.browser_download_url:latest.html_url;
      $("latest-date").textContent="Publicado em "+date(latest.published_at);
      $("latest-version").textContent=latest.name||latest.tag_name||"NEX";
      const description=(latest.body||"Build experimental do NEX para Android.").replace(/\s+/g," ").trim();
      $("latest-description").textContent=description.slice(0,260)+(description.length>260?"…":"");
      $("latest-download").href=apk?apk.browser_download_url:latest.html_url;
      $("latest-download").textContent=apk?"Baixar APK ↓":"Abrir release ↗";
      $("latest-size").textContent=apk?"APK Android · "+size(apk.size):"Consulte os arquivos";
      const assetsBox=$("release-assets");assetsBox.replaceChildren();
      if(apk)assetsBox.append(link("APK · "+size(apk.size),apk.browser_download_url));
      if(checksum)assetsBox.append(link("SHA-256",checksum.browser_download_url));
      assetsBox.append(link("Notas completas",latest.html_url));
      list.replaceChildren();items.slice(0,6).forEach(x=>list.append(row(x)));
    }catch(error){
      $("latest-version").textContent="Consulte as releases";
      $("latest-description").textContent="Não foi possível carregar as versões automaticamente neste momento.";
      list.replaceChildren();const p=document.createElement("p");p.className="error";p.textContent="Falha ao consultar o GitHub. Os downloads continuam disponíveis na página oficial de releases.";list.append(p);
    }
  }
  load();
})();