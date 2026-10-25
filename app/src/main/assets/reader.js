// Turns a chapter of an EPUB into pages you flip sideways, like a printed book.
// The chapter is laid out in screen-wide CSS columns (see readerCss in Reader.kt) and the body
// slides left one screen per page. Talks to the app through window.Reader:
//   Reader.start()            where to open: "" (first page), "end", "f:<0..1>" or "c:<offset>"
//   Reader.onPage(page, n, startOffset, bookmarkId, snippet)
//                             the page now showing, where its text starts, a bookmark on it (or
//                             ""), and the first words on it
//   Reader.highlights()       this chapter's highlights: [[start, end, id], …]
//   Reader.bookmarks()        this chapter's bookmarks: [[offset, id], …]
//   Reader.onSelection(bool)  whether some text is selected (for the Highlight button)
// Positions in the text ("offsets") count characters from the start of the chapter's text, so
// they stay put whatever the text size.
//   Reader.next() / prev()    flip past the end / start of this chapter
//   Reader.toggleChrome()     a tap in the middle of the page
(function () {
  var R = window.Reader;
  var page = 0;
  var pages = 1;
  var width = 1;
  var ready = false;

  function body() { return document.body; }

  function viewWidth() { return document.documentElement.clientWidth || window.innerWidth || 0; }
  function viewHeight() { return window.innerHeight || document.documentElement.clientHeight || 0; }

  // A WebView can lay the page out before it has its real size, when the screen height is still
  // tiny; pages sized from that hold one line each. So the page size is set in pixels from the
  // measured screen (see readerCss), and only once the screen has a believable size.
  function sizeKnown() { return viewWidth() > 100 && viewHeight() > 200; }

  function applySize() {
    var html = document.documentElement;
    html.style.setProperty('--page-w', viewWidth() + 'px');
    html.style.setProperty('--page-h', viewHeight() + 'px');
  }

  function measure() {
    applySize();
    width = viewWidth() || 1;
    var b = body();
    if (!b) return;
    var t = b.style.transform;
    b.style.transform = 'none';
    // The last column can stop short of the screen edge, so round up when it's more than a sliver.
    pages = Math.max(1, Math.ceil((b.scrollWidth - 8) / width));
    b.style.transform = t;
  }

  function show(p) {
    page = Math.max(0, Math.min(pages - 1, p));
    var b = body();
    if (b) b.style.transform = 'translateX(' + (-page * width) + 'px)';
    report();
  }

  function report() {
    if (!R) return;
    var start = firstOffsetOnPage();
    var here = '';
    for (var i = 0; i < bookmarks.length; i++) {
      if (pageOfOffset(bookmarks[i][0]) === page) { here = bookmarks[i][1]; break; }
    }
    R.onPage(page, pages, Math.max(0, start), here, start >= 0 ? snippet(start) : '');
  }

  // ----- Positions in the text -----

  function textNodes() {
    var out = [];
    var b = body();
    if (!b) return out;
    var w = document.createTreeWalker(b, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) out.push(n);
    return out;
  }

  function offsetOf(container, off) {
    var r = document.createRange();
    r.setStart(body(), 0);
    r.setEnd(container, off);
    return r.toString().length;
  }

  function locate(n) {
    var nodes = textNodes(), pos = 0;
    for (var i = 0; i < nodes.length; i++) {
      var len = nodes[i].data.length;
      if (n < pos + len) return { node: nodes[i], off: n - pos };
      pos += len;
    }
    var last = nodes[nodes.length - 1];
    return last ? { node: last, off: last.data.length } : null;
  }

  function pageOfOffset(n) {
    var loc = locate(n);
    if (!loc) return 0;
    var r = document.createRange();
    r.setStart(loc.node, loc.off);
    r.setEnd(loc.node, Math.min(loc.off + 1, loc.node.data.length));
    var rects = r.getClientRects();
    var rect = rects.length ? rects[0] : (loc.node.parentElement && loc.node.parentElement.getBoundingClientRect());
    if (!rect) return 0;
    return Math.max(0, Math.min(pages - 1, Math.floor((rect.left + page * width) / width)));
  }

  function charLeft(node, i) {
    var r = document.createRange();
    r.setStart(node, i);
    r.setEnd(node, i + 1);
    var rects = r.getClientRects();
    return rects.length ? rects[0].left : -Infinity;
  }

  // Where the text on the current page starts, or -1 if the page has no text.
  function firstOffsetOnPage() {
    var nodes = textNodes(), pos = 0;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i], len = node.data.length;
      if (node.data.trim()) {
        var r = document.createRange();
        r.selectNodeContents(node);
        var rects = r.getClientRects(), visible = false, startsHere = false;
        for (var k = 0; k < rects.length; k++) {
          if (rects[k].width > 0 && rects[k].left < width && rects[k].right > 0) {
            visible = true;
            if (k === 0 && rects[k].left >= 0) startsHere = true;
            break;
          }
        }
        if (visible) {
          if (startsHere) return pos;
          // The paragraph began on an earlier page: find its first character on this one.
          var lo = 0, hi = len - 1;
          while (lo < hi) {
            var mid = (lo + hi) >> 1;
            if (charLeft(node, mid) >= 0) hi = mid; else lo = mid + 1;
          }
          return pos + lo;
        }
      }
      pos += len;
    }
    return -1;
  }

  function snippet(n) {
    var text = textNodes().map(function (t) { return t.data; }).join('');
    return text.substr(n, 140).replace(/\s+/g, ' ').trim();
  }

  // ----- Highlights and bookmarks -----

  var bookmarks = [];
  var XHTML = 'http://www.w3.org/1999/xhtml';

  function mark(start, end, id) {
    var nodes = textNodes(), pos = 0;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i], len = node.data.length;
      var a = Math.max(start, pos), b = Math.min(end, pos + len);
      if (a < b && node.data.slice(a - pos, b - pos).trim()) {
        var target = node;
        if (b - pos < len) target.splitText(b - pos);
        if (a - pos > 0) target = target.splitText(a - pos);
        var m = document.createElementNS(XHTML, 'mark');
        m.setAttribute('class', '__hl');
        m.setAttribute('data-id', id);
        target.parentNode.insertBefore(m, target);
        m.appendChild(target);
      }
      pos += len;
    }
  }

  function unmark(id) {
    var marks = document.querySelectorAll('mark.__hl');
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i];
      if (m.getAttribute('data-id') !== id) continue;
      var parent = m.parentNode;
      while (m.firstChild) parent.insertBefore(m.firstChild, m);
      parent.removeChild(m);
      parent.normalize();
    }
  }

  function selectionInfo() {
    var sel = window.getSelection && window.getSelection();
    if (!sel || !sel.rangeCount || sel.isCollapsed) return null;
    var r = sel.getRangeAt(0);
    if (!body().contains(r.commonAncestorContainer)) return null;
    var text = r.toString();
    if (!text.trim()) return null;
    return { start: offsetOf(r.startContainer, r.startOffset), end: offsetOf(r.endContainer, r.endOffset), text: text.replace(/\s+/g, ' ').trim() };
  }

  function hasSelection() {
    var sel = window.getSelection && window.getSelection();
    return !!(sel && String(sel).trim().length > 0);
  }

  var selTimer = 0;
  document.addEventListener('selectionchange', function () {
    clearTimeout(selTimer);
    selTimer = setTimeout(function () { if (R && R.onSelection) R.onSelection(hasSelection()); }, 150);
  });

  function parseList(json) {
    try { var v = JSON.parse(json); return Array.isArray(v) ? v : []; } catch (e) { return []; }
  }

  function pageOf(el) {
    if (!el) return 0;
    var left = el.getBoundingClientRect().left + page * width;
    return Math.floor(Math.max(0, left) / width);
  }

  function fraction() { return pages > 1 ? page / (pages - 1) : 0; }

  function next() {
    if (page < pages - 1) show(page + 1);
    else if (R) R.next();
  }

  function prev() {
    if (page > 0) show(page - 1);
    else if (R) R.prev();
  }

  function relayout() {
    var f = fraction();
    measure();
    show(Math.round(f * (pages - 1)));
  }

  function idFromHash(hash) {
    try { return decodeURIComponent((hash || '').replace(/^#/, '')); } catch (e) { return ''; }
  }

  var waits = 0;
  function onReady() {
    if (ready) return;
    if (!sizeKnown() && waits++ < 50) { // wait up to 5 s for the screen size
      setTimeout(onReady, 100);
      return;
    }
    ready = true;
    if (R && R.highlights) parseList(R.highlights()).forEach(function (h) { mark(h[0], h[1], h[2]); });
    if (R && R.bookmarks) bookmarks = parseList(R.bookmarks());
    measure();
    var start = R ? String(R.start()) : '';
    if (start === 'end') {
      show(pages - 1);
    } else if (start.indexOf('c:') === 0) {
      show(0);
      show(pageOfOffset(parseInt(start.slice(2), 10) || 0));
    } else if (start.indexOf('f:') === 0) {
      show(Math.round(parseFloat(start.slice(2)) * (pages - 1)));
    } else if (location.hash) {
      show(0);
      show(pageOf(document.getElementById(idFromHash(location.hash))));
    } else {
      show(0);
    }
    document.documentElement.className += ' __ready';
  }

  // Swipe sideways to turn the page.
  var sx = 0, sy = 0;
  document.addEventListener('touchstart', function (e) {
    var t = e.touches[0];
    sx = t.clientX; sy = t.clientY;
  }, { passive: true });
  document.addEventListener('touchend', function (e) {
    var t = e.changedTouches[0];
    var dx = t.clientX - sx, dy = t.clientY - sy;
    if (hasSelection()) return; // dragging the selection handles, not turning the page
    if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.3) {
      if (dx < 0) next(); else prev();
    }
  }, { passive: true });

  // Tap the right side for the next page, the left side for the previous one, the middle for the menu.
  document.addEventListener('click', function (e) {
    var link = e.target.closest ? e.target.closest('a[href]') : null;
    if (link) {
      var url = new URL(link.getAttribute('href'), location.href);
      if (url.hash && url.origin + url.pathname === location.origin + location.pathname) {
        e.preventDefault();
        show(pageOf(document.getElementById(idFromHash(url.hash))));
      }
      return; // links to other chapters load normally; the app opens web links in the browser
    }
    var sel = window.getSelection && window.getSelection();
    if (sel && String(sel).length > 0) return;
    var x = e.clientX / width;
    if (x < 0.3) prev();
    else if (x > 0.7) next();
    else if (R) R.toggleChrome();
  });

  window.addEventListener('resize', function () { if (ready) relayout(); });
  // Some WebViews change size without a resize event; check now and then too.
  var lastSize = '';
  setInterval(function () {
    var size = viewWidth() + 'x' + viewHeight();
    if (ready && sizeKnown() && size !== lastSize) relayout();
    lastSize = size;
  }, 1000);
  window.addEventListener('load', function () { setTimeout(onReady, 30); });
  // Don't wait forever for a slow or broken image.
  document.addEventListener('DOMContentLoaded', function () { setTimeout(onReady, 1500); });

  window.__reader = {
    next: next,
    prev: prev,
    relayout: function () { if (ready) relayout(); },
    setCss: function (css) {
      var s = document.getElementById('__reader_css');
      if (s) s.textContent = css;
      if (ready) setTimeout(relayout, 60);
    },
    page: function () { return page; },
    pages: function () { return pages; },
    selection: function () { return selectionInfo(); },
    clearSelection: function () { var sel = window.getSelection(); if (sel) sel.removeAllRanges(); },
    mark: function (start, end, id) { mark(start, end, id); report(); },
    unmark: function (id) { unmark(id); },
    setBookmarks: function (json) { bookmarks = parseList(json); if (ready) report(); },
    goTo: function (offset) { show(pageOfOffset(offset)); }
  };
})();
