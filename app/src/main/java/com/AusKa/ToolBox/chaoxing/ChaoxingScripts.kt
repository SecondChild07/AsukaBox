package com.AusKa.ToolBox.chaoxing

/**
 * 注入到学习通页面里的脚本。
 *
 * 三条原则（也是计划书里最容易踩空的地方）：
 *  1. 只在页面里**读**东西、**点**元素，绝不猜像素坐标 —— WebView 里没有坐标这回事；
 *  2. 每个函数都返回 JSON 字符串，Kotlin 侧只负责展示/决策，定位逻辑全留在页面里；
 *  3. 幂等：BOOTSTRAP 只挂一次 `window.__cs`，页面刷新会重挂，重复注入不会叠加监听。
 *
 * 注意：这里的脚本是在**顶层文档**里跑的。学习通把作业/考试塞在跨域 iframe 里，
 * `evaluateJavascript` 到不了那些 iframe —— [probe] 会把 iframe 的 src 带回来，
 * 由调用方决定「把那个地址提到顶层打开再注入」，这是纯 WebView 方案唯一稳的路子。
 */
internal object ChaoxingScripts {

    /** 桌面版 UA：学习通的作业/考试列表只在桌面布局里出，手机 UA 会被导到简陋的 m 站 */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** 登录页（fid 留空 = 普通账号登录；学校账号走 SSO 由用户自己点） */
    const val LOGIN_URL = "https://passport2.chaoxing.com/login?fid=&refer=http://i.chaoxing.com"

    /** 个人空间：登录成功后基本都落在这里 */
    const val HOME_URL = "https://i.chaoxing.com"

    /**
     * 挂在页面上的工具集。
     *
     * probe()  —— 报告当前页是什么（登录页？题目页？几个 iframe？）
     * pick()   —— 按字母点选择题选项，点完回读 check_answer 核验
     * fill()   —— 给第 N 个输入框写值，派发 input/change
     * scan()   —— 列出页面里的 iframe（跨域时只能拿到 src）
     */
    val BOOTSTRAP: String = """
        (function () {
          if (window.__cs && window.__cs.version >= 3) return;
          var VERSION = 3;

          function all(sel, root) {
            try {
              return Array.prototype.slice.call((root || document).querySelectorAll(sel));
            } catch (e) {
              return [];
            }
          }
          function txt(el) {
            if (!el) return '';
            var t = el.innerText || el.textContent || '';
            return String(t).replace(/\s+/g, ' ').trim();
          }
          function hostOf(u) {
            try { return new URL(u, location.href).host; } catch (e) { return ''; }
          }
          function frames() {
            return all('iframe').map(function (f, i) {
              var src = f.getAttribute('src') || '';
              return { i: i, id: f.id || '', src: src, host: hostOf(src) };
            });
          }

          // 选择题：学习通的选项是 span.num_option，选中后 class 里出现 check_answer
          function choices() { return all('span.num_option'); }
          function isChecked(el) {
            return String(el.getAttribute('class') || '').indexOf('check_answer') >= 0;
          }
          function pick(letters) {
            var want = String(letters || '').toUpperCase().replace(/[^A-Z]/g, '');
            var out = { t: 'pick', picked: [], missing: [], already: 0 };
            choices().forEach(function (el) {
              var key = txt(el).charAt(0).toUpperCase();
              if (want.indexOf(key) < 0) return;
              if (isChecked(el)) { out.already++; out.picked.push(key); return; }
              el.click();
              out.picked.push(key);
            });
            want.split('').forEach(function (k) {
              if (out.picked.indexOf(k) < 0) out.missing.push(k);
            });
            return out;
          }

          // 填空/简答：设值之后必须派发 input + change，否则页面绑定不认
          function typeInto(el, value) {
            if (!el) return false;
            el.focus();
            el.value = String(value);
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            return String(el.value) === String(value);
          }
          function fill(index, value) {
            var inputs = all('input[type=text], input[type=number], input:not([type]), textarea')
              .filter(function (el) { return !el.disabled && !el.readOnly; });
            var el = inputs[index];
            if (!el) return { t: 'fill', ok: false, reason: 'no-input', count: inputs.length };
            return { t: 'fill', ok: typeInto(el, value), index: index, count: inputs.length };
          }
          function inputCount() {
            return all('input[type=text], input:not([type]), textarea').filter(function (el) {
              return !el.disabled && !el.readOnly;
            }).length;
          }

          // UEditor 的正文在子 iframe 的 body 里，同源（about:blank）时能直接写
          function ueditorWrite(value) {
            var out = { t: 'ueditor', written: 0, blocked: 0 };
            all('iframe').forEach(function (f) {
              var id = (f.id || '') + ' ' + (f.getAttribute('src') || '');
              if (!/ueditor/i.test(id)) return;
              try {
                var doc = f.contentDocument;
                var body = doc && doc.body;
                if (!body) { out.blocked++; return; }
                body.innerHTML = String(value || '')
                  .replace(/&/g, '&amp;')
                  .replace(/</g, '&lt;')
                  .replace(/\n/g, '<br>');
                body.dispatchEvent(new Event('input', { bubbles: true }));
                out.written++;
              } catch (e) {
                out.blocked++;
              }
            });
            return out;
          }

          function probe() {
            var d = document.documentElement;
            return {
              t: 'probe',
              host: location.host,
              path: location.pathname,
              title: document.title,
              onLoginPage: location.host.indexOf('passport2') >= 0,
              questions: all('.questionLi, .TiMu, .queBox, [data-cs-q]').length,
              choices: choices().length,
              checked: choices().filter(isChecked).length,
              inputs: inputCount(),
              hasFrames: all('iframe').length,
              scrollY: Math.round(window.scrollY || 0),
              innerH: Math.round(window.innerHeight || 0),
              docH: Math.round((d && d.scrollHeight) || 0),
              bodyH: Math.round((document.body && document.body.scrollHeight) || 0),
              innerW: Math.round(window.innerWidth || 0),
              frames: frames().slice(0, 8)
            };
          }

          window.__cs = {
            version: VERSION,
            probe: probe,
            pick: pick,
            fill: fill,
            ueditor: ueditorWrite,
            text: txt,
            frames: frames
          };

          try {
            if (window.__csBridge && window.__csBridge.emit) {
              window.__csBridge.emit(JSON.stringify({
                t: 'ready', host: location.host, path: location.pathname
              }));
            }
          } catch (e) {}
        })();
    """.trimIndent()

    /**
     * 探测：返回 JSON 字符串。
     * 页面还没挂上 __cs（比如导航到一半）时返回 t=no-bootstrap，不当成错误。
     */
    val PROBE: String = """
        (function () {
          try {
            if (!window.__cs) return JSON.stringify({ t: 'no-bootstrap', host: location.host });
            return JSON.stringify(window.__cs.probe());
          } catch (e) {
            return JSON.stringify({ t: 'error', message: String(e) });
          }
        })();
    """.trimIndent()

    /**
     * 把页面拉回顶部。
     *
     * WebView 会记住上一页的滚动位置，学习通登录页的输入框正好在中间偏上，
     * 不拉回来的话打开就是「从登录按钮开始」的半截页面。
     */
    val SCROLL_TOP: String = """
        (function () {
          try {
            window.scrollTo(0, 0);
            if (document.documentElement) document.documentElement.scrollTop = 0;
            if (document.body) document.body.scrollTop = 0;
          } catch (e) {}
          return '1';
        })();
    """.trimIndent()

    /**
     * 登录页的版式修补。
     *
     * passport2 的桌面登录页把表单块摆到了可视区上方（body 只有 200 多像素高，
     * 表单却从负坐标开始画），在手机 WebView 里打开时输入框直接看不见。
     * 这里量一下表单实际位置，把 body 整体推到视口里 —— 只对 passport2 生效。
     */
    val LOGIN_FIX: String = """
        (function () {
          try {
            if (location.host.indexOf('passport2') < 0) return 'skip';
            var anchors = document.querySelectorAll(
              'input, .loginDiv, .login-box, .login_main'
            );
            var top = null;
            for (var i = 0; i < anchors.length; i++) {
              var r = anchors[i].getBoundingClientRect();
              if (r.height <= 0 || r.width <= 0) continue;
              top = (top === null) ? r.top : Math.min(top, r.top);
            }
            if (top === null) return 'no-anchor';
            if (top >= 14) return 'ok';
            document.body.style.transform = 'translateY(' + Math.round(18 - top) + 'px)';
            return 'shifted';
          } catch (e) {
            return 'err';
          }
        })();
    """.trimIndent()

    /**
     * 扫当前页的作业/考试链接。
     *
     * 学习通的作业入口散落在各门课的页面里，没有统一列表接口，
     * 所以这里按 href 关键字挑链接，再往上找一层容器读课程名和状态字样。
     * 拿不准的状态一律报 unknown，交给 Kotlin 侧显示「待确认」，不瞎猜成「待做」。
     */
    val SCAN_TASKS: String = """
        (function () {
          try {
            var list = [];
            var seen = {};
            var anchors = document.querySelectorAll('a[href]');
            for (var i = 0; i < anchors.length && list.length < 40; i++) {
              var a = anchors[i];
              var href = a.getAttribute('href') || '';
              if (!/(work|exam|homework|job|task)/i.test(href)) continue;
              var title = (a.innerText || a.textContent || '').replace(/\s+/g, ' ').trim();
              if (title.length < 2 || title.length > 60) continue;
              var abs = href;
              try { abs = new URL(href, location.href).href; } catch (e) {}
              if (seen[abs]) continue;
              seen[abs] = 1;

              var box = a;
              var course = '';
              for (var d = 0; d < 4 && box; d++) {
                var node = box.parentElement;
                if (!node) break;
                var cls = String(node.className || '');
                if (/course|kecheng|class/i.test(cls)) {
                  course = (node.innerText || '').replace(/\s+/g, ' ').trim().split(' ')[0];
                  break;
                }
                box = node;
              }

              var line = (box ? (box.innerText || '') : title).replace(/\s+/g, ' ');
              var status = 'unknown';
              if (/(已完成|已提交|待批阅|已批阅)/.test(line)) status = 'done';
              else if (/(已截止|已过期|逾期|已结束)/.test(line)) status = 'expired';
              else if (/(未提交|待提交|去完成|立即完成|开始作业|进入作业)/.test(line)) status = 'todo';

              list.push({
                course: course.slice(0, 20),
                title: title,
                status: status,
                url: abs
              });
            }
            return JSON.stringify({ t: 'tasks', count: list.length, items: list });
          } catch (e) {
            return JSON.stringify({ t: 'error', message: String(e) });
          }
        })();
    """.trimIndent()

    /**
     * 课程列表页：把每门课的 courseId / classId 抠出来。
     *
     * 地址参数大小写两种写法都出现过（`courseid` 与 `courseId`），所以统一 lowercase 后取。
     * 没有 clazzid 的链接（比如课程首页轮播）跳过 —— 拼不出作业页地址。
     */
    val COURSES: String = """
        (function () {
          try {
            var out = [];
            var anchors = document.querySelectorAll('a[href]');
            for (var i = 0; i < anchors.length && out.length < 40; i++) {
              var a = anchors[i];
              var href = a.getAttribute('href') || '';
              if (!/courseid=/i.test(href)) continue;
              if (!/clazzid=/i.test(href)) continue;
              var q = {};
              href.replace(/[?&]([^=&#]+)=([^&#]*)/g, function (_, k, v) {
                q[String(k).toLowerCase()] = decodeURIComponent(v || '');
              });
              var cid = q['courseid'] || '';
              var kid = q['clazzid'] || '';
              if (!cid || !kid) continue;
              // 课程卡片结构（线上实测）：
              //   div.course[id=c_COURSEID]
              //     div.course-cover > a[href*=courseid] > img   ← 封面链接，没有文字
              //     div.course-info  > h3.inlineBlock / a.color1 / span.course-name  ← 课名在这里
              // 所以不能拿「封面链接所在容器的 innerText」，那里面是「Progress: 64/64 100%」。
              var name = '';
              var card = a.closest('div.course, li, dd');
              if (card) {
                var n = card.querySelector('span.course-name, h3, a.color1');
                if (n) name = (n.innerText || n.textContent || '').replace(/\s+/g, ' ').trim();
              }
              if (!name) name = (a.innerText || a.textContent || '').replace(/\s+/g, ' ').trim();
              if (!name) name = String(a.getAttribute('title') || '').trim();
              if (!name && card) {
                var img = card.querySelector('img[alt]');
                if (img) name = String(img.getAttribute('alt') || '').trim();
              }
              name = name.replace(/\s+/g, ' ').slice(0, 40);
              if (!name || /progress|course has|^\d+\s*\/\s*\d+/i.test(name)) continue;
              out.push({
                courseId: cid,
                classId: kid,
                cpi: q['cpi'] || '',
                course: name
              });
            }
            return JSON.stringify({ t: 'courses', count: out.length, items: out });
          } catch (e) {
            return JSON.stringify({ t: 'error', message: String(e) });
          }
        })();
    """.trimIndent()

    /**
     * 课程页里点「作业」或「考试」tab。
     *
     * 这两个 tab 是 `a[data-url="..."]`（不是 href），点下去由页面脚本自己拼出带
     * enc / cpi / stuenc / t 的签名地址塞进 iframe —— 那段签名没法在 Kotlin 里复刻，
     * 只能点出来再读。
     */
    fun clickTabJs(kind: String): String {
        val key = if (kind == "exam") "exam-list" else "work/list"
        val label = if (kind == "exam") "考试" else "作业"
        return """
            (function () {
              try {
                var a = document.querySelector('a[data-url*="$key"]');
                if (!a) {
                  var cands = document.querySelectorAll('a, li, span');
                  for (var i = 0; i < cands.length; i++) {
                    var tx = (cands[i].innerText || '').replace(/\s+/g, '');
                    if (tx === '$label') { a = cands[i]; break; }
                  }
                }
                if (!a) return JSON.stringify({ t: 'tab', ok: false });
                a.click();
                return JSON.stringify({ t: 'tab', ok: true });
              } catch (e) {
                return JSON.stringify({ t: 'tab', ok: false, message: String(e) });
              }
            })();
        """.trimIndent()
    }

    /**
     * 读 iframe 里那个已经签好名的地址。
     *
     * iframe 是跨域的，内容碰不到，但 `src` 属性在父页面的 DOM 里，读得到。
     */
    fun frameSrcJs(kind: String): String {
        val key = if (kind == "exam") "exam-list" else "work/list"
        return """
            (function () {
              try {
                var frames = document.querySelectorAll('iframe');
                for (var i = 0; i < frames.length; i++) {
                  var src = frames[i].getAttribute('src') || frames[i].src || '';
                  if (src.indexOf('$key') >= 0) return JSON.stringify({ t: 'src', url: src });
                }
                var any = '';
                for (var j = 0; j < frames.length; j++) {
                  var s2 = frames[j].getAttribute('src') || '';
                  if (s2 && s2.indexOf('chaoxing.com') >= 0) { any = s2; break; }
                }
                return JSON.stringify({ t: 'src', url: any });
              } catch (e) {
                return JSON.stringify({ t: 'src', url: '', message: String(e) });
              }
            })();
        """.trimIndent()
    }

    /**
     * 作业页 / 考试页：一行一条记录。
     *
     * 结构差异很大（ul.ulDiv / table / div.list），所以按「行里有没有指向作业的链接」筛，
     * 再用文字判状态。状态判不准就给 unknown，由界面显示「待确认」，不硬猜成待完成。
     */
    val ROWS: String = """
        (function () {
          try {
            var out = [];
            var nodes = document.querySelectorAll('li, tr, .ulDiv li, .workList li, .list li');
            for (var i = 0; i < nodes.length && out.length < 60; i++) {
              var el = nodes[i];
              var txt = (el.innerText || '').replace(/\s+/g, ' ').trim();
              if (!txt || txt.length < 4 || txt.length > 240) continue;
              var link = el.querySelector('a[href]');
              if (!link) continue;
              var href = link.getAttribute('href') || '';
              if (!/(work|homework|exam|job|task)/i.test(href)) continue;
              var title = (link.innerText || '').replace(/\s+/g, ' ').trim();
              if (!title || title.length < 2) continue;

              var status = 'unknown';
              if (/(已截止|已过期|逾期|已结束|已关闭)/.test(txt)) status = 'expired';
              else if (/(已完成|已提交|待批阅|已批阅|已通过|已交)/.test(txt)) status = 'done';
              else if (/(未提交|待提交|未完成|去完成|立即完成|开始|做作业)/.test(txt)) status = 'todo';

              var due = '';
              var m = txt.match(/(\d{4}-\d{1,2}-\d{1,2}(?:\s+\d{1,2}:\d{2})?|\d{1,2}-\d{1,2}\s+\d{1,2}:\d{2})/);
              if (m) due = m[1];

              var abs = href;
              try { abs = new URL(href, location.href).href; } catch (e) {}

              out.push({
                title: title.slice(0, 60),
                status: status,
                due: due,
                url: abs,
                kind: /(exam|考试)/i.test(href + ' ' + txt) ? 'exam' : 'work'
              });
            }
            return JSON.stringify({ t: 'rows', count: out.length, items: out });
          } catch (e) {
            return JSON.stringify({ t: 'error', message: String(e) });
          }
        })();
    """.trimIndent()

    fun pickJs(letters: String): String =
        "JSON.stringify(window.__cs ? window.__cs.pick(" + quote(letters) + ") : {t:'no-bootstrap'})"

    fun fillJs(index: Int, value: String): String =
        "JSON.stringify(window.__cs ? window.__cs.fill(" + index + ", " + quote(value) + ") : {t:'no-bootstrap'})"

    fun ueditorJs(value: String): String =
        "JSON.stringify(window.__cs ? window.__cs.ueditor(" + quote(value) + ") : {t:'no-bootstrap'})"

    /** 最小的 JS 字符串字面量转义：只需要处理反斜杠、引号和换行 */
    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        sb.append('"')
        for (c in raw) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(' ') else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
