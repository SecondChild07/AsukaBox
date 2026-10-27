(function () {
  function txt(el) { return el ? (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  var blocks = document.querySelectorAll('.TiMu');
  var out = [];
  for (var i = 0; i < blocks.length; i++) {
    var b = blocks[i];
    var eds = [];
    var ifr = b.querySelectorAll('iframe');
    for (var j = 0; j < ifr.length; j++) {
      var f = ifr[j];
      var t = '';
      try {
        var d = f.contentDocument;
        if (d && d.body) t = (d.body.innerText || d.body.textContent || '').replace(/\s+/g, ' ').trim();
      } catch (e) { t = 'ERR:' + e.message; }
      eds.push({ id: f.id || '', src: (f.getAttribute('src') || '').slice(0, 40), text: t.slice(0, 4000) });
    }
    var audio = [];
    var ifr2 = b.querySelectorAll('iframe');
    for (var k = 0; k < ifr2.length; k++) {
      var s = ifr2[k].getAttribute('src') || '';
      var m = s.match(/objectid=([0-9a-f\-]+)/i);
      if (m) audio.push(m[1]);
    }
    out.push({
      idx: i,
      stem: txt(b.querySelector('.Zy_TItle, .mark_name, .fl.textCon, .questionTit')).slice(0, 1500),
      blanks: b.querySelectorAll('input[type=text], input:not([type]), textarea').length,
      editorCount: eds.length,
      editors: eds,
      audio: audio
    });
  }
  return JSON.stringify(out);
})()
