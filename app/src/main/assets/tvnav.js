/*
 * TV / game-controller navigation layer for Jellyseerr.
 *
 * Injected into every page by MainActivity. The Android side intercepts the
 * remote / gamepad buttons and calls into window.__tvnav:
 *
 *   move('up'|'down'|'left'|'right')  spatial navigation between controls
 *   activate()                        "OK" / A button
 *   back()  -> true if handled here   (blur input, close dialog / menu)
 *   search()                          jump to the search box and open keyboard
 *   home()                            go to the Discover page
 *   page(+1|-1)                       page down / up
 *   top()                             scroll to top
 *   scroll(dy)                        smooth scrolling from the right stick
 *
 * A glowing ring follows the selected element so it is obvious from the couch.
 */
(function () {
  'use strict';
  if (window.__tvnav || window.top !== window) return;

  var VIEWPORT = window.__TVNAV_VIEWPORT || 0;

  function bridge() {
    return window.TvBridge || null;
  }

  // ---------------------------------------------------------------------------
  // Viewport: force a fixed CSS width so Jellyseerr renders its desktop layout
  // (sidebar + rows of posters) at a size that is readable from the couch.
  // ---------------------------------------------------------------------------
  var headObserved = false;

  function applyViewport() {
    if (!VIEWPORT || !document.head) return;
    var want = 'width=' + VIEWPORT + ', user-scalable=no';
    var metas = document.head.querySelectorAll('meta[name="viewport"]');
    if (!metas.length) {
      var m = document.createElement('meta');
      m.name = 'viewport';
      m.content = want;
      document.head.appendChild(m);
      return;
    }
    for (var i = 0; i < metas.length; i++) {
      if (metas[i].getAttribute('content') !== want) metas[i].setAttribute('content', want);
    }
  }

  function watchHead() {
    if (headObserved || !document.head) return false;
    headObserved = true;
    applyViewport();
    injectStyle();
    new MutationObserver(applyViewport).observe(document.head, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['content'],
    });
    return true;
  }

  if (!watchHead()) {
    var docObserver = new MutationObserver(function () {
      if (watchHead()) docObserver.disconnect();
    });
    docObserver.observe(document, { childList: true, subtree: true });
  }

  // ---------------------------------------------------------------------------
  // Focus ring
  // ---------------------------------------------------------------------------
  var STYLE_ID = 'tvnav-style';
  var RING_ID = 'tvnav-ring';

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var s = document.createElement('style');
    s.id = STYLE_ID;
    s.textContent =
      '#' + RING_ID + '{position:fixed;left:0;top:0;width:0;height:0;pointer-events:none;' +
      'z-index:2147483647;box-sizing:border-box;border:3px solid #fff;border-radius:10px;' +
      'box-shadow:0 0 0 3px rgba(99,102,241,.95),0 0 26px 8px rgba(99,102,241,.6);' +
      'opacity:0;transition:transform .12s ease-out,width .12s ease-out,height .12s ease-out,opacity .15s;}' +
      '#' + RING_ID + '.on{opacity:1}' +
      'html.tvnav-active,html.tvnav-active *{cursor:none!important}' +
      'html.tvnav-active *:focus{outline:none!important}';
    (document.head || document.documentElement).appendChild(s);
  }

  var ring = null;
  var current = null;
  var ringPlaced = false;

  function ensureRing() {
    if (!document.body) return null;
    if (!ring) {
      ring = document.createElement('div');
      ring.id = RING_ID;
    }
    if (!ring.isConnected) {
      injectStyle();
      document.body.appendChild(ring);
      ringPlaced = false;
    }
    return ring;
  }

  function tick() {
    var r = ensureRing();
    if (r) {
      if (current && current.isConnected) {
        var b = current.getBoundingClientRect();
        if (b.width > 0 && b.height > 0) {
          var pad = 5;
          if (!ringPlaced) {
            // Jump straight to the first target instead of sliding in from 0,0.
            r.style.transition = 'none';
          }
          r.style.transform = 'translate(' + (b.left - pad) + 'px,' + (b.top - pad) + 'px)';
          r.style.width = b.width + pad * 2 + 'px';
          r.style.height = b.height + pad * 2 + 'px';
          if (!ringPlaced) {
            void r.offsetWidth;
            r.style.transition = '';
            ringPlaced = true;
          }
          if (!r.classList.contains('on')) r.classList.add('on');
        } else {
          r.classList.remove('on');
        }
      } else if (r.classList.contains('on')) {
        r.classList.remove('on');
      }
    }
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);

  function updateRingShape(el) {
    var r = ensureRing();
    if (!r || !el) return;
    var radius = 10;
    try {
      var cs = getComputedStyle(el);
      var px = parseFloat(cs.borderTopLeftRadius) || 0;
      if (px > 0) radius = Math.min(px + 4, 9999);
    } catch (e) {}
    r.style.borderRadius = radius + 'px';
  }

  // ---------------------------------------------------------------------------
  // Candidate discovery
  // ---------------------------------------------------------------------------
  var SELECTOR = [
    'a[href]',
    'button',
    'input:not([type="hidden"])',
    'select',
    'textarea',
    'summary',
    '[role="button"]',
    '[role="link"]',
    '[role="menuitem"]',
    '[role="menuitemcheckbox"]',
    '[role="menuitemradio"]',
    '[role="option"]',
    '[role="tab"]',
    '[role="checkbox"]',
    '[role="radio"]',
    '[role="switch"]',
    '[role="combobox"]',
    '[tabindex]:not([tabindex="-1"])',
    '[contenteditable="true"]',
    '[contenteditable=""]',
  ].join(',');

  var TEXT_TYPES = [
    'text', 'search', 'email', 'url', 'tel', 'password', 'number',
    'date', 'datetime-local', 'month', 'time', 'week',
  ];

  function isTextInput(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return !el.readOnly && !el.disabled;
    if (el.tagName !== 'INPUT') return false;
    var t = (el.getAttribute('type') || 'text').toLowerCase();
    return TEXT_TYPES.indexOf(t) >= 0 && !el.readOnly && !el.disabled;
  }

  function isCombobox(el) {
    if (!el) return false;
    return (
      el.getAttribute('role') === 'combobox' ||
      el.hasAttribute('aria-autocomplete') ||
      el.getAttribute('aria-haspopup') === 'listbox'
    );
  }

  function isDisabled(el) {
    return el.disabled === true || el.getAttribute('aria-disabled') === 'true';
  }

  function isVisible(el, r) {
    if (r.width < 2 || r.height < 2) return false;
    if (el.checkVisibility) {
      if (
        !el.checkVisibility({
          checkOpacity: true,
          checkVisibilityCSS: true,
          opacityProperty: true,
          visibilityProperty: true,
        })
      ) {
        return false;
      }
    } else {
      var cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none') return false;
    }
    return true;
  }

  function scrollsX(el) {
    if (el.scrollWidth <= el.clientWidth + 1) return false;
    var o = getComputedStyle(el).overflowX;
    return o === 'auto' || o === 'scroll';
  }

  function scrollsY(el) {
    if (el.scrollHeight <= el.clientHeight + 1) return false;
    var o = getComputedStyle(el).overflowY;
    return o === 'auto' || o === 'scroll';
  }

  function hasScrollParentX(el) {
    for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) {
      if (scrollsX(p)) return true;
    }
    return false;
  }

  function viewportArea() {
    return window.innerWidth * window.innerHeight;
  }

  // The topmost open dialog / slide-over, if any. Navigation is confined to it.
  function modalScope() {
    var best = null;
    // Later in document order = on top (portals are appended to <body>).
    // A dialog nested inside an overlay also counts as "later", so the
    // tighter scope wins.
    function consider(el) {
      if (!el || el === ring) return;
      if (!best || best.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING) best = el;
    }

    var dialogs = document.querySelectorAll('[role="dialog"],[aria-modal="true"]');
    for (var i = 0; i < dialogs.length; i++) {
      var d = dialogs[i];
      var r = d.getBoundingClientRect();
      if (isVisible(d, r) && d.querySelector(SELECTOR)) consider(d);
    }

    var area = viewportArea();
    (function walk(node, depth) {
      var kids = node.children;
      for (var k = 0; k < kids.length; k++) {
        var c = kids[k];
        if (c === ring || c.tagName === 'SCRIPT' || c.tagName === 'STYLE') continue;
        var cs = getComputedStyle(c);
        if (cs.display === 'none') continue;
        if (cs.position === 'fixed') {
          var rc = c.getBoundingClientRect();
          if (
            rc.width * rc.height >= area * 0.5 &&
            cs.pointerEvents !== 'none' &&
            parseFloat(cs.opacity) > 0.05 &&
            !c.querySelector('main') &&
            c.querySelector(SELECTOR)
          ) {
            consider(c);
          }
          continue;
        }
        if (depth < 3) walk(c, depth + 1);
      }
    })(document.body || document.documentElement, 0);

    // Prefer the role=dialog panel inside a full-screen overlay.
    if (best && best.getAttribute('role') !== 'dialog') {
      var inner = best.querySelector('[role="dialog"]');
      if (inner && isVisible(inner, inner.getBoundingClientRect()) && inner.querySelector(SELECTOR)) {
        best = inner;
      }
    }
    return best;
  }

  function candidates(scope) {
    scope = scope || modalScope() || document.body || document.documentElement;
    var nodes = scope.querySelectorAll(SELECTOR);
    var area = viewportArea();
    var vw = window.innerWidth;
    var list = [];
    var set = new Set();
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el === ring || isDisabled(el)) continue;
      if (el.tagName === 'INPUT' && el.type === 'hidden') continue;
      var r = el.getBoundingClientRect();
      if (!isVisible(el, r)) continue;
      // Skip big containers (e.g. <main tabindex="0">).
      if (r.width * r.height > area * 0.6) continue;
      // Skip off-canvas things (hidden drawers) unless they live in a horizontal scroller.
      if ((r.right <= 0 || r.left >= vw) && !hasScrollParentX(el)) continue;
      list.push({ el: el, r: r });
      set.add(el);
    }
    // Collapse nested controls: a poster card containing its own link and
    // buttons is a single stop; OK opens it.
    return list.filter(function (c) {
      for (var p = c.el.parentElement; p && p !== scope; p = p.parentElement) {
        if (set.has(p)) {
          var pr = p.getBoundingClientRect();
          if (pr.width * pr.height < area * 0.25) return false;
        }
      }
      return true;
    });
  }

  function validCurrent(scope) {
    if (!current || !current.isConnected) return null;
    var r = current.getBoundingClientRect();
    if (!isVisible(current, r)) return null;
    if (scope && !scope.contains(current)) return null;
    return current;
  }

  function inViewport(r) {
    return r.bottom > 0 && r.top < window.innerHeight && r.right > 0 && r.left < window.innerWidth;
  }

  // Initial focus: first control in the main content area that is on screen.
  function initial(list) {
    if (!list.length) return null;
    var main = document.querySelector('main');
    var vis = list.filter(function (c) {
      return inViewport(c.r);
    });
    var pool = vis.length ? vis : list;
    var inMain = main
      ? pool.filter(function (c) {
          return main.contains(c.el) && c.r.top >= 0;
        })
      : [];
    if (inMain.length) pool = inMain;
    pool = pool.slice().sort(function (a, b) {
      var dy = a.r.top - b.r.top;
      if (Math.abs(dy) > 8) return dy;
      return a.r.left - b.r.left;
    });
    return pool[0].el;
  }

  // ---------------------------------------------------------------------------
  // Spatial navigation
  // ---------------------------------------------------------------------------
  function score(dir, a, b) {
    var acx = (a.left + a.right) / 2;
    var acy = (a.top + a.bottom) / 2;
    var bcx = (b.left + b.right) / 2;
    var bcy = (b.top + b.bottom) / 2;
    var major, gap, off;
    switch (dir) {
      case 'down':
        if (!(b.top >= a.top + 1 && bcy > acy + 1 && b.bottom > a.bottom)) return Infinity;
        major = Math.max(0, b.top - a.bottom);
        gap = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right));
        off = Math.abs(bcx - acx);
        break;
      case 'up':
        if (!(b.bottom <= a.bottom - 1 && bcy < acy - 1 && b.top < a.top)) return Infinity;
        major = Math.max(0, a.top - b.bottom);
        gap = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right));
        off = Math.abs(bcx - acx);
        break;
      case 'right':
        if (!(b.left >= a.left + 1 && bcx > acx + 1 && b.right > a.right)) return Infinity;
        major = Math.max(0, b.left - a.right);
        gap = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom));
        off = Math.abs(bcy - acy);
        break;
      case 'left':
        if (!(b.right <= a.right - 1 && bcx < acx - 1 && b.left < a.left)) return Infinity;
        major = Math.max(0, a.left - b.right);
        gap = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom));
        off = Math.abs(bcy - acy);
        break;
      default:
        return Infinity;
    }
    // Strongly prefer targets that line up with the current one ("in beam").
    return major + (gap > 0 ? 120 + gap * 3 : 0) + off * 0.15;
  }

  function scrollParent(el, axis) {
    for (var p = el ? el.parentElement : null; p && p !== document.body; p = p.parentElement) {
      if (axis === 'x' ? scrollsX(p) : scrollsY(p)) return p;
    }
    return null;
  }

  function ensureVisible(el) {
    var r = el.getBoundingClientRect();
    var h = window.innerHeight;
    var w = window.innerWidth;
    var marginY = Math.min(140, h * 0.15);
    var outY = r.top < marginY || r.bottom > h - marginY * 0.6;
    var outX = r.left < 0 || r.right > w;
    var sx = scrollParent(el, 'x');
    if (sx) {
      var pr = sx.getBoundingClientRect();
      if (r.left < pr.left + 8 || r.right > pr.right - 8) outX = true;
    }
    if (outY || outX) {
      try {
        el.scrollIntoView({
          behavior: 'smooth',
          block: outY ? 'center' : 'nearest',
          inline: outX ? 'center' : 'nearest',
        });
      } catch (e) {
        el.scrollIntoView(false);
      }
    }
  }

  function mouse(type, el, related, bubbles) {
    try {
      var r = el.getBoundingClientRect();
      el.dispatchEvent(
        new MouseEvent(type, {
          bubbles: bubbles !== false,
          cancelable: true,
          view: window,
          relatedTarget: related || null,
          clientX: r.left + r.width / 2,
          clientY: r.top + r.height / 2,
        })
      );
    } catch (e) {}
  }

  // Simulate hover so cards show their details overlay when selected.
  function hover(prev, next) {
    if (prev && prev.isConnected) {
      mouse('mouseout', prev, next);
      mouse('mouseleave', prev, next, false);
    }
    if (next) {
      var rel = prev && prev.isConnected ? prev : null;
      mouse('mouseover', next, rel);
      mouse('mouseenter', next, rel, false);
      mouse('mousemove', next, null);
    }
  }

  // Element that was selected before a dialog opened; selection returns there
  // when the dialog closes.
  var beforeModal = null;

  function returnTarget(scope) {
    if (scope || !beforeModal) return null;
    var el = beforeModal;
    beforeModal = null;
    if (!el.isConnected || !isVisible(el, el.getBoundingClientRect())) return null;
    return el;
  }

  function setCurrent(el, opts) {
    opts = opts || {};
    var prev = current;
    if (prev && prev.isConnected && prev !== el) {
      var scope = modalScope();
      if (scope && scope.contains(el) && !scope.contains(prev)) beforeModal = prev;
    }
    current = el;
    document.documentElement.classList.add('tvnav-active');
    var ae = document.activeElement;
    if (ae && ae !== el && isTextInput(ae)) ae.blur();
    if (!isTextInput(el)) {
      try {
        el.focus({ preventScroll: true });
      } catch (e) {}
    }
    if (prev !== el) hover(prev, el);
    if (!opts.noScroll) ensureVisible(el);
    updateRingShape(el);
  }

  function scrollFallback(dir, from) {
    var dy = 0;
    var dx = 0;
    if (dir === 'down') dy = window.innerHeight * 0.6;
    if (dir === 'up') dy = -window.innerHeight * 0.6;
    if (dir === 'right') dx = window.innerWidth * 0.6;
    if (dir === 'left') dx = -window.innerWidth * 0.6;
    var target = from ? scrollParent(from, dx ? 'x' : 'y') : null;
    if (!target && dy) {
      var scope = modalScope();
      if (scope) target = scrollsY(scope) ? scope : scrollParent(scope.querySelector(SELECTOR), 'y');
    }
    if (target) target.scrollBy({ left: dx, top: dy, behavior: 'smooth' });
    else if (dy) window.scrollBy({ top: dy, behavior: 'smooth' });
  }

  function sendKey(el, key, keyCode) {
    var init = { key: key, code: key, keyCode: keyCode, which: keyCode, bubbles: true, cancelable: true };
    el.dispatchEvent(new KeyboardEvent('keydown', init));
    el.dispatchEvent(new KeyboardEvent('keyup', init));
  }

  function move(dir) {
    var scope = modalScope();
    var ae = document.activeElement;

    // Inside an autocomplete/select-style input, arrows drive its list.
    if (ae && isTextInput(ae) && isCombobox(ae) && (dir === 'up' || dir === 'down')) {
      if (ae.getAttribute('aria-expanded') === 'true' || dir === 'down') {
        sendKey(ae, dir === 'down' ? 'ArrowDown' : 'ArrowUp', dir === 'down' ? 40 : 38);
        return true;
      }
    }

    var cur = validCurrent(scope);
    var list = candidates(scope);
    if (!cur) {
      var init = restoreTarget(list) || returnTarget(scope) || initial(list);
      if (init) setCurrent(init);
      else scrollFallback(dir, null);
      return true;
    }
    var from = cur.getBoundingClientRect();
    var best = null;
    var bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (c.el === cur || c.el.contains(cur) || cur.contains(c.el)) continue;
      var s = score(dir, from, c.r);
      if (s < bestScore) {
        bestScore = s;
        best = c.el;
      }
    }
    if (best) setCurrent(best);
    else scrollFallback(dir, cur);
    return true;
  }

  // ---------------------------------------------------------------------------
  // Activation
  // ---------------------------------------------------------------------------
  function innerLink(el) {
    var links = el.querySelectorAll('a[href]');
    var r = el.getBoundingClientRect();
    var area = r.width * r.height;
    for (var i = 0; i < links.length; i++) {
      var lr = links[i].getBoundingClientRect();
      if (lr.width * lr.height >= area * 0.5) return links[i];
    }
    return null;
  }

  function press(el) {
    var tag = el.tagName;
    if (tag !== 'A' && tag !== 'BUTTON' && tag !== 'INPUT') {
      // Some widgets react to pointer/mouse down rather than click.
      try {
        var r = el.getBoundingClientRect();
        var o = {
          bubbles: true,
          cancelable: true,
          view: window,
          clientX: r.left + r.width / 2,
          clientY: r.top + r.height / 2,
          pointerType: 'mouse',
          isPrimary: true,
          button: 0,
        };
        if (window.PointerEvent) el.dispatchEvent(new PointerEvent('pointerdown', o));
        el.dispatchEvent(new MouseEvent('mousedown', o));
        if (window.PointerEvent) el.dispatchEvent(new PointerEvent('pointerup', o));
        el.dispatchEvent(new MouseEvent('mouseup', o));
      } catch (e) {}
    }
    el.click();
  }

  var pendingSelect = null;

  function openSelect(el) {
    var b = bridge();
    var opts = [];
    for (var i = 0; i < el.options.length; i++) opts.push(el.options[i].text);
    if (b && b.chooseOption) {
      pendingSelect = el;
      var label = '';
      if (el.id) {
        var l = document.querySelector('label[for="' + el.id + '"]');
        if (l) label = l.textContent.trim();
      }
      b.chooseOption(JSON.stringify({ title: label, options: opts, selected: el.selectedIndex }));
    } else if (opts.length) {
      applySelectTo(el, (el.selectedIndex + 1) % opts.length);
    }
  }

  function applySelectTo(el, idx) {
    if (!el || idx < 0 || idx >= el.options.length) return;
    var setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set;
    setter.call(el, el.options[idx].value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function openKeyboard(el) {
    try {
      el.focus({ preventScroll: true });
      var len = (el.value || '').length;
      if (el.setSelectionRange && el.type !== 'number' && el.type !== 'email') el.setSelectionRange(len, len);
    } catch (e) {}
    var b = bridge();
    if (b && b.showKeyboard) b.showKeyboard();
  }

  function activate() {
    var scope = modalScope();
    var el = validCurrent(scope);
    if (!el) {
      var list = candidates(scope);
      var init = restoreTarget(list) || returnTarget(scope) || initial(list);
      if (init) setCurrent(init);
      return true;
    }
    remember(el);

    if (isTextInput(el)) {
      if (isCombobox(el) && document.activeElement === el && el.getAttribute('aria-expanded') === 'true') {
        sendKey(el, 'Enter', 13);
      } else {
        openKeyboard(el);
      }
      return true;
    }
    if (el.tagName === 'SELECT') {
      openSelect(el);
      return true;
    }
    if (el.getAttribute('role') === 'link' && el.tagName !== 'A') {
      // Jellyseerr poster cards: the real link is revealed inside on hover.
      var a = innerLink(el);
      if (a) {
        a.click();
      } else {
        press(el);
        setTimeout(function () {
          var a2 = innerLink(el);
          if (a2) a2.click();
        }, 150);
      }
      return true;
    }
    press(el);
    return true;
  }

  // ---------------------------------------------------------------------------
  // Back
  // ---------------------------------------------------------------------------
  var CLOSE_RE = /^\s*(cancel|close|done|dismiss|no|back)\s*$/i;

  function findCloseButton(scope) {
    var btns = scope.querySelectorAll('button,[role="button"]');
    var fallback = null;
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      if (!isVisible(b, b.getBoundingClientRect()) || isDisabled(b)) continue;
      var label = (b.getAttribute('aria-label') || '') + ' ' + (b.textContent || '');
      if (CLOSE_RE.test(b.textContent || '') || CLOSE_RE.test(b.getAttribute('aria-label') || '')) return b;
      if (!fallback && /close/i.test(label)) fallback = b;
    }
    return fallback;
  }

  function closeModal(scope) {
    var target = current && scope.contains(current) ? current : scope;
    sendKey(target, 'Escape', 27);
    setTimeout(function () {
      if (!scope.isConnected || !isVisible(scope, scope.getBoundingClientRect())) return;
      var btn = findCloseButton(scope);
      if (btn) {
        btn.click();
        return;
      }
      // Click on the dimmed backdrop (outside the dialog panel).
      var overlay = scope.parentElement;
      while (overlay && getComputedStyle(overlay).position !== 'fixed') overlay = overlay.parentElement;
      if (overlay && overlay !== scope) {
        mouse('mousedown', overlay);
        mouse('mouseup', overlay);
        mouse('click', overlay);
      }
    }, 200);
  }

  function back() {
    var ae = document.activeElement;
    if (ae && isTextInput(ae)) {
      ae.blur();
      if (current !== ae) current = ae;
      return true;
    }
    var scope = modalScope();
    if (scope) {
      closeModal(scope);
      current = null;
      setTimeout(function () {
        if (modalScope() || validCurrent()) return;
        var el = returnTarget(null);
        if (el) setCurrent(el, { noScroll: true });
      }, 450);
      return true;
    }
    // Open drop-down menus / list boxes.
    var open = document.querySelectorAll('[aria-expanded="true"][aria-haspopup]');
    for (var i = 0; i < open.length; i++) {
      var btn = open[i];
      if (!isVisible(btn, btn.getBoundingClientRect())) continue;
      sendKey(document.activeElement || btn, 'Escape', 27);
      (function (b) {
        setTimeout(function () {
          if (b.getAttribute('aria-expanded') === 'true') b.click();
          if (b.isConnected) setCurrent(b, { noScroll: true });
        }, 120);
      })(btn);
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Remember selection per page so Back returns to the poster you opened.
  // ---------------------------------------------------------------------------
  var memory = {};
  var pendingRestore = null;

  function signature(el) {
    var img = el.querySelector('img');
    return [
      el.tagName,
      el.getAttribute('href') || '',
      img ? img.getAttribute('src') || '' : (el.textContent || '').trim().slice(0, 60),
    ].join('|');
  }

  function remember(el) {
    try {
      var list = candidates();
      var idx = -1;
      for (var i = 0; i < list.length; i++) {
        if (list[i].el === el) {
          idx = i;
          break;
        }
      }
      memory[location.href] = { sig: signature(el), idx: idx };
    } catch (e) {}
  }

  function restoreTarget(list) {
    var m = pendingRestore;
    if (!m) return null;
    for (var i = 0; i < list.length; i++) {
      if (signature(list[i].el) === m.sig) {
        pendingRestore = null;
        return list[i].el;
      }
    }
    return null;
  }

  window.addEventListener('popstate', function () {
    var m = memory[location.href];
    if (!m) return;
    pendingRestore = m;
    current = null;
    [200, 600, 1200, 2000].forEach(function (t) {
      setTimeout(function () {
        if (!pendingRestore || validCurrent()) return;
        var el = restoreTarget(candidates());
        if (el) setCurrent(el);
      }, t);
    });
  });

  // ---------------------------------------------------------------------------
  // Other actions
  // ---------------------------------------------------------------------------
  function search() {
    var inputs = document.querySelectorAll(
      '#search_field,input[type="search"],input[name="search"],input[placeholder*="earch"]'
    );
    for (var i = 0; i < inputs.length; i++) {
      var el = inputs[i];
      if (isVisible(el, el.getBoundingClientRect())) {
        setCurrent(el, { noScroll: true });
        openKeyboard(el);
        return true;
      }
    }
    return false;
  }

  function home() {
    var links = document.querySelectorAll('a[href="/"],a[href="/discover"]');
    for (var i = 0; i < links.length; i++) {
      if (isVisible(links[i], links[i].getBoundingClientRect())) {
        links[i].click();
        current = null;
        window.scrollTo({ top: 0 });
        return true;
      }
    }
    location.assign('/');
    return true;
  }

  function scrollTargetY() {
    var scope = modalScope();
    var cur = validCurrent(scope);
    var t = cur ? scrollParent(cur, 'y') : null;
    if (!t && scope) t = scrollsY(scope) ? scope : null;
    return t;
  }

  function page(d) {
    var t = scrollTargetY();
    var amount = window.innerHeight * 0.8 * (d > 0 ? 1 : -1);
    if (t) t.scrollBy({ top: amount, behavior: 'smooth' });
    else window.scrollBy({ top: amount, behavior: 'smooth' });
    var cur = validCurrent();
    var cx = cur ? cur.getBoundingClientRect().left : window.innerWidth / 2;
    setTimeout(function () {
      var list = candidates().filter(function (c) {
        return c.r.top >= 0 && c.r.bottom <= window.innerHeight;
      });
      if (!list.length) return;
      var best = null;
      var bs = Infinity;
      for (var i = 0; i < list.length; i++) {
        var r = list[i].r;
        var s = Math.abs((r.top + r.bottom) / 2 - window.innerHeight / 2) + Math.abs(r.left - cx) * 0.5;
        if (s < bs) {
          bs = s;
          best = list[i].el;
        }
      }
      if (best) setCurrent(best, { noScroll: true });
    }, 450);
    return true;
  }

  function top() {
    var t = scrollTargetY();
    if (t) t.scrollTo({ top: 0, behavior: 'smooth' });
    window.scrollTo({ top: 0, behavior: 'smooth' });
    current = null;
    return true;
  }

  function scroll(dy) {
    var t = scrollTargetY();
    if (t) t.scrollBy(0, dy);
    else window.scrollBy(0, dy);
    return true;
  }

  // ---------------------------------------------------------------------------
  // Keep Android informed about text fields so it can route keys correctly.
  // ---------------------------------------------------------------------------
  var lastText = null;

  function notifyInput() {
    var ae = document.activeElement;
    var t = isTextInput(ae);
    if (t !== lastText) {
      lastText = t;
      var b = bridge();
      if (b && b.setTextInputActive) b.setTextInputActive(t);
    }
    if (t && current !== ae) {
      current = ae;
      updateRingShape(ae);
    }
  }

  document.addEventListener('focusin', notifyInput, true);
  document.addEventListener(
    'focusout',
    function () {
      setTimeout(notifyInput, 0);
    },
    true
  );

  // A real mouse / touch takes over from the ring.
  ['mousedown', 'touchstart'].forEach(function (type) {
    window.addEventListener(
      type,
      function (e) {
        if (e.isTrusted) {
          current = null;
          document.documentElement.classList.remove('tvnav-active');
        }
      },
      true
    );
  });

  window.__tvnav = {
    move: move,
    activate: activate,
    back: back,
    search: search,
    home: home,
    page: page,
    top: top,
    scroll: scroll,
    applySelect: function (idx) {
      var el = pendingSelect;
      pendingSelect = null;
      applySelectTo(el, idx);
      if (el && el.isConnected) setCurrent(el, { noScroll: true });
    },
    // exposed for debugging / tests
    _candidates: candidates,
    _current: function () {
      return current;
    },
    _modalScope: modalScope,
  };
})();
