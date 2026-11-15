(function(){
  var SRC=['https://cdn.jsdelivr.net/gh/IMGMINEYT/launcher-db@main/data/addons.json',
           'https://raw.githubusercontent.com/IMGMINEYT/launcher-db/main/data/addons.json'];
  var RAW='https://raw.githubusercontent.com/IMGMINEYT/launcher-db/main/',CDN='https://cdn.jsdelivr.net/gh/IMGMINEYT/launcher-db@main/';
  var PAGE=24, state={all:[],q:'',type:'all',sort:'new',shown:PAGE};
  var $=function(id){return document.getElementById(id)};
  var grid=$('grid'),count=$('count'),more=$('more'),chips=$('chips'),dlg=$('dlg');

  function fmt(n){if(!n)return '';var u=['B','KB','MB','GB'],i=0;while(n>=1024&&i<3){n/=1024;i++}return (i>1?n.toFixed(1):Math.round(n))+' '+u[i]}
  function hash(s){var h=2166136261;for(var i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619)}return h>>>0}
  function art(name){ // generated pixel banner when an addon has no image
    var c=document.createElement('canvas');c.width=32;c.height=18;var x=c.getContext('2d'),h=hash(name),hue=h%360;
    x.fillStyle='hsl('+hue+',35%,18%)';x.fillRect(0,0,32,18);
    for(var i=0;i<32*18;i++){h=Math.imul(h^(h>>>13),1274126177)>>>0;var l=(h%100);if(l<35){x.fillStyle='hsl('+hue+',40%,'+(22+(l%14))+'%)';x.fillRect(i%32,(i/32)|0,1,1)}}
    x.fillStyle='hsl('+((hue+40)%360)+',55%,55%)';x.fillRect(13,6,6,6);
    return c;
  }
  function banner(a,eager){
    var b=document.createElement('div');b.className='banner'+(a.icon?' icon':'');
    if(a.banner){var im=new Image();im.alt=a.name;im.decoding='async';if(!eager)im.loading='lazy';im.referrerPolicy='no-referrer';
      im.onerror=function(){b.textContent='';b.appendChild(art(a.name))};im.src=a.banner;b.appendChild(im)}
    else b.appendChild(art(a.name));
    return b;
  }
  function types(){var t={};state.all.forEach(function(a){t[a.type||'addon']=1});return Object.keys(t).sort()}
  function drawChips(){
    chips.textContent='';var ts=types();if(ts.length<2)return;
    ['all'].concat(ts).forEach(function(t){var b=document.createElement('button');b.className='chip';b.type='button';
      b.textContent=t==='all'?'All':t.charAt(0).toUpperCase()+t.slice(1);b.setAttribute('aria-pressed',String(state.type===t));
      b.onclick=function(){state.type=t;state.shown=PAGE;drawChips();render()};chips.appendChild(b)});
  }
  function nz(t){return String(t||'').toLowerCase().replace(/[^a-z0-9 ]+/g,' ').replace(/\s+/g,' ').trim()}
  function lev(a,b,m){var n=a.length,k=b.length;if(Math.abs(n-k)>m)return m+1;var p=[],i,j;for(j=0;j<=k;j++)p[j]=j;
    for(i=1;i<=n;i++){var c=[i],lo=i;for(j=1;j<=k;j++){var v=Math.min(p[j]+1,c[j-1]+1,p[j-1]+(a[i-1]===b[j-1]?0:1));c[j]=v;if(v<lo)lo=v}
      if(lo>m)return m+1;p=c}return p[k]}
  function tokScore(t,txt,name){
    if(txt.indexOf(t)>-1)return (name?3:1)+(txt.indexOf(t)===0?1:0);
    if(t.length<4)return 0;var m=t.length>7?2:1,w=txt.split(' ');
    for(var i=0;i<w.length;i++){if(w[i].length>=3&&lev(t,w[i],m)<=m)return name?2:0.6;
      if(w[i].length>t.length&&lev(t,w[i].slice(0,t.length),m)<=m)return name?1.6:0.5}
    return 0}
  function list(){
    var q=nz(state.q),toks=q?q.split(' '):[],r=[];
    state.all.forEach(function(a){
      if(state.type!=='all'&&(a.type||'addon')!==state.type)return;
      if(!toks.length){a._s=0;r.push(a);return}
      var n=nz(a.name),d=nz(a.description),sc=0;
      for(var i=0;i<toks.length;i++){var x=tokScore(toks[i],n,true)||tokScore(toks[i],d,false);if(!x)return;sc+=x}
      a._s=sc;r.push(a)});
    if(toks.length){r.sort(function(a,b){return b._s-a._s});return r}
    if(state.sort==='az')r.sort(function(a,b){return a.name.localeCompare(b.name)});
    else if(state.sort==='size')r.sort(function(a,b){return (b.size||0)-(a.size||0)});
    else r.sort(function(a,b){return String(b.added||'').localeCompare(String(a.added||''))});
    return r;
  }
  function dl(a,cls){var l=document.createElement('a');l.className=cls||'btn';l.textContent='Download';l.href=a.download;l.rel='noopener';return l}
  function open(a){
    dlg.textContent='';dlg.appendChild(banner(a,true));var d=document.createElement('div');d.className='in';
    var h=document.createElement('h2');h.textContent=a.name;var p=document.createElement('div');p.className='desc';p.textContent=a.description||'';
    var r=document.createElement('div');r.className='row';r.appendChild(dl(a));
    var c=document.createElement('button');c.className='btn ghost';c.textContent='Close';c.onclick=function(){dlg.close()};r.appendChild(c);
    if(a.size){var s=document.createElement('span');s.style.cssText='color:var(--mute);align-self:center';s.textContent=fmt(a.size);r.appendChild(s)}
    d.appendChild(h);if(a.description)d.appendChild(p);d.appendChild(r);
    if(a.description&&a.source&&/^https:\/\//.test(a.source)){var sc=document.createElement('p');sc.className='desc';sc.appendChild(document.createTextNode('Details from '));var sl=document.createElement('a');sl.href=a.source;sl.rel='noopener nofollow';sl.target='_blank';sl.textContent=a.source.replace(/^https:\/\/(www\.)?/,'').split('/')[0];sc.appendChild(sl);d.appendChild(sc)}
    dlg.appendChild(d);
    if(dlg.showModal)dlg.showModal();else dlg.setAttribute('open','');
  }
  function card(a,i){
    var c=document.createElement('article');c.className='card';c.appendChild(banner(a,i<4));
    var b=document.createElement('div');b.className='body';
    var h=document.createElement('h3');h.textContent=a.name;var p=document.createElement('p');p.textContent=a.description||'';
    var m=document.createElement('div');m.className='meta';var s=document.createElement('span');s.textContent=fmt(a.size);
    m.appendChild(s);m.appendChild(dl(a));b.appendChild(h);if(a.description)b.appendChild(p);b.appendChild(m);c.appendChild(b);
    c.addEventListener('click',function(e){if(e.target.closest('a'))return;open(a)});c.style.cursor='pointer';
    return c;
  }
  function render(){
    var r=list();grid.textContent='';
    r.slice(0,state.shown).forEach(function(a,i){grid.appendChild(card(a,i))});
    count.textContent=state.all.length?(r.length+' addon'+(r.length===1?'':'s')):'';
    more.hidden=r.length<=state.shown;
    if(!r.length){var e=document.createElement('div');e.className='empty';e.textContent=state.all.length?'Nothing matches that search.':'No addons yet. Check back soon.';grid.appendChild(e)}
  }
  function skeleton(){for(var i=0;i<6;i++){var s=document.createElement('div');s.className='skel';grid.appendChild(s)}}
  function get(i){return fetch(SRC[i]+(i?'?t='+Math.floor(Date.now()/60000):''),{cache:'no-cache'}).then(function(r){if(!r.ok)throw 0;return r.json()})}
  function load(){
    skeleton();
    var cached=null;try{cached=JSON.parse(sessionStorage.getItem('ad1')||'null')}catch(e){}
    if(cached&&Date.now()-cached.t<120000){state.all=cached.d;drawChips();render();return}
    get(0).catch(function(){return get(1)}).then(function(j){
      state.all=(j.addons||[]).filter(function(a){return a&&a.name&&a.download&&/^https:\/\//.test(a.download)}).map(function(a){if(a.banner&&a.banner.indexOf(RAW)===0)a.banner=CDN+a.banner.slice(RAW.length);return a});
      try{sessionStorage.setItem('ad1',JSON.stringify({t:Date.now(),d:state.all}))}catch(e){}
      drawChips();render();
    }).catch(function(){grid.textContent='';var e=document.createElement('div');e.className='empty';e.textContent='Could not load the list. Refresh in a moment.';grid.appendChild(e)});
  }
  var tm;$('q').addEventListener('input',function(e){clearTimeout(tm);tm=setTimeout(function(){state.q=e.target.value;state.shown=PAGE;render()},30)});
  $('sort').addEventListener('change',function(e){state.sort=e.target.value;state.shown=PAGE;render()});
  more.addEventListener('click',function(){state.shown+=PAGE;render()});
  dlg.addEventListener('click',function(e){if(e.target===dlg)dlg.close()});
  load();
})();
