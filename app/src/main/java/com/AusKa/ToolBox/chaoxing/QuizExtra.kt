package com.AusKa.ToolBox.chaoxing

/**
 * 读「这一题现在选的是什么」，以及把一道题的选择清空。
 *
 * 单独一份而不是塞进 [QuizScripts]，是因为这两段逻辑只在「覆盖旧答案」时才用到，
 * 放在一起更容易看出它们改的是同一件事：先清再点。
 */
internal object QuizExtra {

    private const val BLOCKS = "('.questionLi')"

    private fun block(index: Int): String = "document.querySelectorAll($BLOCKS)[$index]"

    /** 回 `{t:'current',checked:['A','B'],filled:2}`，无块回 `{t:'none'}` */
    fun currentJs(index: Int): String = """
        (function () {
          try {
            var b = ${block(index)};
            if (!b) return JSON.stringify({ t: 'current', checked: [], filled: 0 });
            var picked = [];
            var opts = b.querySelectorAll('span.num_option, span.num_option_dx');
            for (var i = 0; i < opts.length; i++) {
              var cls = opts[i].className || '';
              if (cls.indexOf('check_answer') < 0) continue;
              var holder = opts[i].closest('li') || opts[i];
              var one = holder.querySelector('input[type=checkbox], input[type=radio]');
              if (one && !one.checked) continue;
              var letter = '';
              if (opts[i].hasAttribute('data')) letter = opts[i].getAttribute('data');
              else letter = String.fromCharCode(65 + i);
              picked.push(String(letter).toUpperCase());
            }
            var filled = 0;
            var eds = b.querySelectorAll('iframe');
            for (var j = 0; j < eds.length; j++) {
              var d = null;
              try { d = eds[j].contentDocument; } catch (e) { d = null; }
              if (!d || !d.body) continue;
              var txt = (d.body.innerText || '').replace(/\s+/g, '');
              if (txt) filled++;
            }
            return JSON.stringify({ t: 'current', checked: picked, filled: filled });
          } catch (e) {
            return JSON.stringify({ t: 'error', message: String(e) });
          }
        })()
    """.trimIndent()

    /** 把这一题已选中的选项全部取消（多选才需要），回 `{t:'cleared',n}` */
    fun clearJs(index: Int): String = """
        (function () {
          try {
            var b = ${block(index)};
            if (!b) return JSON.stringify({ t: 'cleared', n: 0 });
            var opts = b.querySelectorAll('span.num_option, span.num_option_dx');
            var n = 0;
            for (var i = 0; i < opts.length; i++) {
              var cls = opts[i].className || '';
              if (cls.indexOf('check_answer') < 0) continue;
              var holder = opts[i].closest('li') || opts[i];
              try { holder.click(); } catch (e) { }
              n++;
            }
            return JSON.stringify({ t: 'cleared', n: n });
          } catch (e) {
            return JSON.stringify({ t: 'error', message: String(e) });
          }
        })()
    """.trimIndent()
}
