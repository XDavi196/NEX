(function(){
  "use strict";
  const projects=[
    {repo:"XDavi196/NEX",name:"NEX",versionId:"nex-version"},
    {repo:"XDavi196/NEX-Shop",name:"NEX Shop",versionId:"shop-version"}
  ];
  const $=id=>document.getElementById(id);
  function date(value){
    const d=new Date(value);
    return Number.isNaN(d.getTime())?"Data indisponível":new Intl.DateTimeFormat("pt-BR",{day:"2-digit",month:"short",year:"numeric"}).format(d);
  }
  function card(project,release){
    const article=document.createElement("article");article.className="release-card";
    const icon=document.createElement("span");icon.className="r-icon";icon.textContent="↓";
    const body=document.createElement("div");
    const label=document.createElement("small");label.textContent=project.name.toUpperCase()+(release.prerelease?" · PRÉ-RELEASE":"");
    const title=document.createElement("h3");title.textContent=release.name||release.tag_name||"Release";
    const info=document.createElement("p");info.textContent=(release.tag_name||"Versão publicada")+" · "+date(release.published_at);
    const asset=(release.assets||[]).find(x=>x.name&&(/\.apk$/i.test(x.name)||/\.exe$/i.test(x.name)||/\.zip$/i.test(x.name)));
    const link=document.createElement("a");link.href=asset?asset.browser_download_url:release.html_url;link.target="_blank";link.rel="noopener";link.textContent=asset?"Baixar "+asset.name+" ↗":"Abrir release e arquivos ↗";
    body.append(label,title,info,link);article.append(icon,body);return article;
  }
  async function loadProject(project){
    try{
      const response=await fetch("https://api.github.com/repos/"+project.repo+"/releases?per_page=5",{headers:{Accept:"application/vnd.github+json"},cache:"no-store"});
      if(!response.ok)throw new Error("API "+response.status);
      const list=(await response.json()).filter(x=>!x.draft&&x.html_url);
      const version=$(project.versionId);
      if(list.length)version.textContent=list[0].tag_name||list[0].name||"Publicada";
      else version.textContent="Sem release";
      return list.slice(0,2).map(x=>card(project,x));
    }catch(_){
      $(project.versionId).textContent="Consultar Releases";
      const fallback=document.createElement("article");fallback.className="release-card";
      const icon=document.createElement("span");icon.className="r-icon";icon.textContent="↗";
      const body=document.createElement("div");const title=document.createElement("h3");title.textContent=project.name;
      const text=document.createElement("p");text.textContent="Não foi possível consultar a API agora.";
      const link=document.createElement("a");link.href="https://github.com/"+project.repo+"/releases";link.target="_blank";link.rel="noopener";link.textContent="Abrir releases ↗";
      body.append(title,text,link);fallback.append(icon,body);return [fallback];
    }
  }
  async function load(){
    const grid=$("release-grid");
    const loading=$("release-loading");
    const batches=await Promise.all(projects.map(loadProject));
    if(loading)loading.remove();
    batches.flat().forEach(item=>grid.append(item));
  }
  load();
})();