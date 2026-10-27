(function(){
  var out=[];
  var ifr=document.querySelectorAll('iframe');
  for(var i=0;i<ifr.length;i++){
    var f=ifr[i]; var id=f.id||'';
    if(!/^ueditor_/.test(id)) continue;
    var imgs=[]; var html='';
    try{
      var d=f.contentDocument;
      if(d){ imgs=[].slice.call(d.querySelectorAll('img')).map(function(x){return x.src}); html=(d.body?d.body.innerHTML:'').slice(0,200); }
    }catch(e){}
    out.push({id:id,imgs:imgs,html:html});
  }
  return JSON.stringify(out);
})()
