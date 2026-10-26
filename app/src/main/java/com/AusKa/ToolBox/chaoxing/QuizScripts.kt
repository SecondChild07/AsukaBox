package com.AusKa.ToolBox.chaoxing

/**
 * 题目抽取与回填脚本。
 *
 * 和学习通打交道最稳的办法是「只认 DOM，不猜接口」：题目、选项、输入框、编辑器全在页面上，
 * 模型只负责给语义答案（字母 / 文本 / 排好的顺序），点哪个、填哪个由这里决定。
 * 每个题目块都单独定位，回填完再回读一遍验证，不靠「点完就当成了」。
 *
 * 注意：学习通的题目块是嵌套的（`.TiMu` 里再套 `.questionLi`），
 * 所以先用 [PICK_BLOCKS] 只留最外层，保证「抽题」和「回填」用的是同一套下标。
 */
internal object QuizScripts {

    /**
     * 题目块列表。
     *
     * 页面上 `.TiMu` 有时候是整个答题区的**外壳**（实测：28 道题只有 1 个 .TiMu，
     * 而 .questionLi 正好 28 个），所以不能把三个选择器并起来用，按优先级取第一个非空的。
     */
    private const val PICK_BLOCKS = """
        (function () {
          var groups = ['.questionLi', '.TiMu', '.queBox'];
          for (var i = 0; i < groups.length; i++) {
            var list = Array.prototype.slice.call(document.querySelectorAll(groups[i]));
            if (list.length > 0) return list;
          }
          return [];
        })()
    """

    private const val TXT_FN = """
        function txt(el) {
          if (!el) return '';
          var t = el.innerText || el.textContent || '';
          return String(t).replace(/\s+/g, ' ').trim();
        }
        function all(sel, root) {
          try { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
          catch (e) { return []; }
        }
    """

    /** 抽出页面上的所有题目，返回 JSON 字符串 */
    val QUESTIONS: String = """
        (function () {
          $TXT_FN
          function first(el, sel) {
            try { return el.querySelector(sel); } catch (e) { return null; }
          }
          function kindOf(stem, opts, inputs, hasEditor, hasAudio) {
            var s = stem || '';
            if (hasAudio) return 'listening';
            if (/听力|听音|音频/.test(s)) return 'listening';
            if (/连线|匹配|配对/.test(s)) return 'connect';
            if (/排序/.test(s)) return 'sort';
            if (/判断/.test(s)) return 'judge';
            if (/多选|不定项/.test(s)) return 'multi';
            if (/单选|单项选择/.test(s)) return 'choice';
            if (/填空/.test(s)) return 'fill';
            if (/简答|论述|名词解释|分析题|计算题|作文|翻译|辨析/.test(s)) return 'essay';
            if (opts.length >= 2) return 'choice';
            if (inputs > 0) return 'fill';
            if (hasEditor) return 'essay';
            return 'unknown';
          }
          var blocks = $PICK_BLOCKS;
          var out = [];
          blocks.forEach(function (b, i) {
            var stemEl = first(b, '.Zy_TItle, .mark_name, .fl.textCon, .questionTit, .queTit') || b;
            var stem = txt(stemEl).slice(0, 1200);
            // 听力大题常常只有「1. (填空题) No.1 No.2 …」，真正的材料在块的上一层容器里，
            // 这时候把外层文本（Section A News Reports + 题干 + 选项）当材料补进来。
            var scopeEl = null;
            try { scopeEl = b.closest('.TiMu') || b.parentElement; } catch (e) {}
            var scopeText = txt(scopeEl);
            if (scopeText.length > stem.length + 40) stem = scopeText.slice(0, 2000);
            var optionEls = all('span.num_option, span.num_option_dx', b);
            var options = optionEls.map(function (o) {
              var row = null;
              try { row = o.closest('.answerBg') || o.closest('li'); } catch (e) {}
              var t = txt(row || o).slice(0, 300);
              // 多选题的选项节点里只有字母（A/B/C/D），正文在同级 .answer_p 里
              if (t.length <= 2) {
                var p = null;
                try { p = row && row.querySelector('.answer_p'); } catch (e) {}
                if (p) t = txt(p).slice(0, 300);
              }
              return t;
            });
            var inputs = all('input[type=text], input:not([type]), textarea', b)
              .filter(function (el) { return !el.disabled && !el.readOnly; }).length;
            var editor = all('iframe', b).some(function (f) {
              return /ueditor/i.test((f.id || '') + ' ' + (f.getAttribute('src') || ''));
            });
            // 填空的可见作答框是每个空一个 ueditor iframe，隐藏 input 只是提交镜像：
            // 计数以可见槽位为准，否则会漏掉「有编辑器但隐藏 input 数量对不上」的页面。
            if (editor) {
              var eds = all('iframe', b).filter(function (f) {
                return /ueditor/i.test((f.id || '') + ' ' + (f.getAttribute('src') || ''));
              }).length;
              if (eds > inputs) inputs = eds;
            }
            var audioEl = first(b, 'audio') || first(b, 'audio source');
            var audio = '';
            if (audioEl) audio = audioEl.getAttribute('src') || audioEl.src || '';
            if (!audio) {
              var any = first(b, 'a[href$=".mp3"], a[href*="audio"]');
              if (any) audio = any.getAttribute('href') || '';
            }
            // 超星听力播发器是 iframe：/module/audioplay.html?objectid=xxxx，
            // 真正音频要用 objectid 去 /ananas/status/<id> 换下载地址，所以这里只留 id。
            if (!audio) {
              var frameList = all('iframe[src*="audioplay"]', b);
              if (frameList.length === 0) frameList = all('iframe[src*="audioplay"]', scopeEl);
              var ids = [];
              frameList.forEach(function (f) {
                var m = String(f.getAttribute('src') || '').match(/objectid=([0-9a-fA-F]+)/);
                if (m && ids.indexOf(m[1]) < 0) ids.push(m[1]);
              });
              if (ids.length > 0) audio = 'object:' + ids.slice(0, 4).join(',');
            }
            // 有的听力作业把题面和选项直接做成图片（超星 ananas 图床），页面上没有可读文字，
            // 这种情况只能把图片交给支持视觉的模型，所以这里把图片地址一起抓出来。
            var imgs = [];
            all('img', scopeEl).forEach(function (im) {
              var s = im.getAttribute('src') || im.getAttribute('data-original') || '';
              if (/ananas/i.test(s) && imgs.indexOf(s) < 0) imgs.push(s);
            });
            if (imgs.length > 3) imgs = imgs.slice(0, 3);
            out.push({
              i: i,
              kind: kindOf(stem, options, inputs, editor, !!audio),
              stem: stem,
              options: options,
              inputs: inputs,
              editor: editor,
              audio: audio,
              images: imgs
            });
          });
          return JSON.stringify({ t: 'questions', count: out.length, items: out });
        })();
    """.trimIndent()

    /**
     * 在某个题目块里选选项。
     *
     * letters 形如 "C" / "AC"。按**顺序下标**点，不按文字匹配 —— 判断题的
     * span 文本就是「对 / 错」，拿字母去比首字符永远匹配不上，这是个真踩过的坑。
     * 点击只发一次（父 li 或里面的 checkbox 二选一），因为超星的点击是 toggle，
     * 先点 li 再点 span 会把刚选上的取消掉。是否成功以点击后回读的 class 为准。
     */
    fun chooseJs(index: Int, letters: String): String {
        val want = quote(letters.uppercase().filter { it in 'A'..'Z' })
        return """
            (function () {
              $TXT_FN
              var want = $want;
              var blocks = $PICK_BLOCKS;
              var b = blocks[$index];
              if (!b) return JSON.stringify({ t: 'choose', ok: false, reason: 'no-block' });
              var opts = all('span.num_option, span.num_option_dx', b);
              function holderOf(o) {
                try { return o.closest('.answerBg') || o.closest('li') || o; } catch (e) { return o; }
              }
              function letterOf(o) {
                var l = o.getAttribute('data') || txt(o).charAt(0) || '';
                return String(l).toUpperCase();
              }
              function isChecked(o) {
                var h = holderOf(o);
                var cls = String(o.getAttribute('class') || '') + ' ' + String(h.getAttribute('class') || '');
                // 单选标 check_answer，多选标 check_answer_dx（实测两种都存在）
                return cls.indexOf('check_answer') >= 0;
              }
              var clicked = [];
              want.split('').forEach(function (k) {
                var target = null;
                opts.forEach(function (o) { if (letterOf(o) === k) target = o; });
                if (!target) return;
                if (isChecked(target)) { clicked.push(k); return; }
                var h = holderOf(target);
                var box = h.querySelector('input[type=checkbox], input[type=radio]');
                if (box) box.click(); else h.click();
                clicked.push(k);
              });
              var checkedNow = [];
              opts.forEach(function (o) {
                if (!isChecked(o)) return;
                var l = letterOf(o);
                if (l && checkedNow.indexOf(l) < 0) checkedNow.push(l);
              });
              var ok = want.split('').every(function (k) { return checkedNow.indexOf(k) >= 0; });
              return JSON.stringify({
                t: 'choose', ok: ok, clicked: clicked, checked: checkedNow
              });
            })();
        """.trimIndent()
    }

    /** 在某个题目块里填第 slot 个空（填空题） */
    fun fillJs(index: Int, slot: Int, value: String): String {
        val v = quote(value)
        return """
            (function () {
              var blocks = $PICK_BLOCKS;
              var b = blocks[$index];
              if (!b) return JSON.stringify({ t: 'fill', ok: false, reason: 'no-block' });
              // 实测：填空/简答的可见作答框其实是每个空一个 UEditor iframe（id=ueditor_N），
              // 真正提交读的是同级的隐藏 input#answerEditorXXXX（display:none）。
              // 所以两个都得写：编辑器 body 给渲染看，隐藏 input 给提交用。
              var eds = Array.prototype.slice.call(b.querySelectorAll('iframe'))
                .filter(function (f) {
                  return /ueditor/i.test((f.id || '') + ' ' + (f.getAttribute('src') || ''));
                });
              var fields = Array.prototype.slice
                .call(b.querySelectorAll('input[type=text], input:not([type]), textarea'))
                .filter(function (el) { return !el.disabled && !el.readOnly; });
              var count = eds.length > 0 ? eds.length : fields.length;
              if (count === 0) return JSON.stringify({ t: 'fill', ok: false, reason: 'no-input', count: 0 });
              var wrote = false;
              if (eds.length > 0) {
                var f = eds[$slot];
                var doc = null;
                try { doc = f.contentDocument; } catch (e) { doc = null; }
                if (doc && doc.body) {
                  doc.body.innerHTML = '<p>' + $v.replace(/&/g, '&amp;').replace(/</g, '&lt;') + '</p>';
                  doc.body.dispatchEvent(new Event('input', { bubbles: true }));
                  doc.body.dispatchEvent(new Event('change', { bubbles: true }));
                  wrote = String(doc.body.innerText || '').indexOf($v) >= 0;
                }
              }
              var hidden = fields[$slot] || fields[0];
              if (hidden) {
                hidden.value = $v;
                hidden.dispatchEvent(new Event('input', { bubbles: true }));
                hidden.dispatchEvent(new Event('change', { bubbles: true }));
                if (eds.length === 0) wrote = String(hidden.value) === $v;
              }
              return JSON.stringify({ t: 'fill', ok: wrote, editors: eds.length, count: count });
            })();
        """.trimIndent()
    }

    /** 简答/论述：写 UEditor 的 body（同源才写得到），没有编辑器就退回 textarea */
    fun essayJs(index: Int, value: String): String {
        val v = quote(value)
        return """
            (function () {
              var blocks = $PICK_BLOCKS;
              var b = blocks[$index];
              if (!b) return JSON.stringify({ t: 'essay', ok: false, reason: 'no-block' });
              var html = $v.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/\n/g, '<br>');
              var wrote = 0;
              var frames = Array.prototype.slice.call(b.querySelectorAll('iframe'));
              frames.forEach(function (f) {
                var id = (f.id || '') + ' ' + (f.getAttribute('src') || '');
                if (!/ueditor/i.test(id)) return;
                try {
                  var doc = f.contentDocument;
                  if (!doc || !doc.body) return;
                  doc.body.innerHTML = html;
                  doc.body.dispatchEvent(new Event('input', { bubbles: true }));
                  wrote++;
                } catch (e) {}
              });
              if (wrote === 0) {
                var ta = b.querySelector('textarea');
                if (ta) {
                  ta.focus();
                  ta.value = $v;
                  ta.dispatchEvent(new Event('input', { bubbles: true }));
                  ta.dispatchEvent(new Event('change', { bubbles: true }));
                  wrote++;
                }
              }
              return JSON.stringify({ t: 'essay', ok: wrote > 0, wrote: wrote });
            })();
        """.trimIndent()
    }

    /** 排序题：按给定字母顺序（对应选项下标）依次点击 */
    fun sortJs(index: Int, order: String): String {
        val seq = quote(order.uppercase().filter { it in 'A'..'Z' })
        return """
            (function () {
              $TXT_FN
              var seq = $seq.split('');
              var blocks = $PICK_BLOCKS;
              var b = blocks[$index];
              if (!b) return JSON.stringify({ t: 'sort', ok: false, reason: 'no-block' });
              var opts = all('span.num_option', b);
              if (opts.length === 0) opts = all('li', b);
              var done = [];
              seq.forEach(function (k) {
                var i = k.charCodeAt(0) - 65;
                if (i < 0 || i >= opts.length) return;
                var row = null;
                try { row = opts[i].closest('li'); } catch (e) {}
                (row || opts[i]).click();
                done.push(k);
              });
              return JSON.stringify({ t: 'sort', ok: done.length > 0, done: done });
            })();
        """.trimIndent()
    }

    /** 音视频：把听力音频速度拉高，方便快点听完（不改答案，只是加速） */
    fun speedJs(rate: Double): String = """
        (function () {
          var list = Array.prototype.slice.call(document.querySelectorAll('audio, video'));
          var n = 0;
          list.forEach(function (a) {
            try { a.playbackRate = $rate; n++; } catch (e) {}
          });
          return JSON.stringify({ t: 'speed', count: n });
        })();
    """.trimIndent()

    /** 回读整个页面：每题选中了哪些选项、填了几处 */
    val VERIFY: String = """
        (function () {
          $TXT_FN
          var blocks = $PICK_BLOCKS;
          var out = [];
          blocks.forEach(function (b, i) {
            var opts = all('span.num_option', b);
            var checked = opts.filter(function (o) {
              return String(o.getAttribute('class') || '').indexOf('check_answer') >= 0;
            }).map(function (o) { return txt(o).charAt(0).toUpperCase(); });
            var filled = all('input[type=text], input:not([type]), textarea', b)
              .filter(function (el) { return String(el.value || '').trim().length > 0; }).length;
            // 填空的可见框是 ueditor iframe，值在 body 里，也得算进去
            var editorFilled = all('iframe', b).filter(function (f) {
              if (!/ueditor/i.test((f.id || '') + ' ' + (f.getAttribute('src') || ''))) return false;
              var d = null;
              try { d = f.contentDocument; } catch (e) { d = null; }
              return !!(d && d.body && String(d.body.innerText || '').trim().length > 0);
            }).length;
            if (editorFilled > filled) filled = editorFilled;
            out.push({ i: i, checked: checked, filled: filled });
          });
          return JSON.stringify({ t: 'verify', items: out });
        })();
    """.trimIndent()

    /** 最小 JS 字面量转义（Kotlin 原始字符串里不能出现 $，这里只做数据拼接） */
    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        sb.append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(' ') else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
