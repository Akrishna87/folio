// Turns a chapter of an EPUB into pages you flip sideways, like a printed book.
// The chapter is laid out in screen-wide CSS columns (see readerCss in Reader.kt) and the body
// slides left one screen per page. Talks to the app through window.Reader:
//   Reader.start()            where to open: "" (first page), "end", or "f:<0..1>"
//   Reader.onPage(page, n)    the page now showing
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
    if (R) R.onPage(page, pages);
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
    measure();
    var start = R ? String(R.start()) : '';
    if (start === 'end') {
      show(pages - 1);
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
    pages: function () { return pages; }
  };
})();
