(function () {
    'use strict';

    // Idempotente: onPageFinished / document-start possono iniettare più volte nello stesso documento
    if (window.top !== window || window.__fsTvNav) return;
    window.__fsTvNav = true;

    var INTERACTIVE_SEL = 'a[href], button, input:not([type="hidden"]), select, textarea, summary, ' +
        '[role="button"], [role="link"], [role="tab"], [role="menuitem"], [role="option"], ' +
        '[tabindex]:not([tabindex="-1"]), [onclick]';
    // Il sito usa molti div/svg con @click (tab EPISODI, icona ricerca, stagioni, +, ★...):
    // li riconosciamo dal cursor:pointer calcolato.
    var POINTER_SCAN_SEL = 'div, span, li, img, svg, i, p, label, h1, h2, h3, h4, h5, h6, td, figure, section, article';
    var NEXT_HANDLE_SEL = '.slide-right, .swiper-button-next, .slick-next, .carousel-control-next, .owl-next, [aria-label="Next"], [aria-label="Successivo"]';
    var PREV_HANDLE_SEL = '.slide-left, .swiper-button-prev, .slick-prev, .carousel-control-prev, .owl-prev, [aria-label="Previous"], [aria-label="Precedente"]';
    var PREFERRED_START_SEL = '.info-button.play, a.play, a[href*="/watch/"]';

    var current = null;
    var lastRect = null;
    var ring = null;
    var ringAnimUntil = 0;
    var mouseMode = false;
    var busyUntil = 0;
    var pointerCache = [];
    var domDirty = true;
    var lastScan = 0;
    var initTimer = null;

    function bridge() { return window.AndroidBridge; }
    function now() { return Date.now(); }

    function isEditable(el) {
        if (!el) return false;
        if (el.tagName === 'TEXTAREA' || el.isContentEditable) return true;
        if (el.tagName !== 'INPUT') return false;
        return !/^(button|submit|reset|checkbox|radio|range|color|file|image)$/i.test(el.type || '');
    }

    function isPlayerPage() {
        return /\/(watch|iframe)\//.test(location.pathname) || !!document.fullscreenElement;
    }

    // ---------------------------------------------------------------- stili + anello di focus
    function injectStyles() {
        if (!window.__fsCss || document.getElementById('fs-tv-styles')) return;
        var style = document.createElement('style');
        style.id = 'fs-tv-styles';
        style.textContent = window.__fsCss;
        (document.head || document.documentElement).appendChild(style);
    }

    function ensureRing() {
        if (ring && ring.isConnected) return ring;
        ring = document.createElement('div');
        ring.id = 'fs-focus-ring';
        document.documentElement.appendChild(ring);
        return ring;
    }

    function hideRing() {
        if (ring) ring.classList.remove('fs-visible');
    }

    function placeRing(r) {
        var el = ensureRing();
        var pad = 4;
        el.style.width = Math.round(r.width + pad * 2) + 'px';
        el.style.height = Math.round(r.height + pad * 2) + 'px';
        el.style.transform = 'translate3d(' + Math.round(r.left - pad) + 'px,' + Math.round(r.top - pad) + 'px,0)';
        el.classList.add('fs-visible');
    }

    function sameRect(a, b) {
        return a && b && Math.abs(a.left - b.left) < 0.5 && Math.abs(a.top - b.top) < 0.5 &&
            Math.abs(a.width - b.width) < 0.5 && Math.abs(a.height - b.height) < 0.5;
    }

    // Segue l'elemento a ogni frame (scroll, caroselli animati, re-render) senza toccare il DOM del sito
    var lostSince = 0;
    function tick() {
        if (current && !mouseMode && !document.fullscreenElement) {
            if (current.isConnected) {
                lostSince = 0;
                var r = current.getBoundingClientRect();
                if (r.width > 0 && r.height > 0) {
                    var animating = now() < ringAnimUntil;
                    if (ring) ring.classList.toggle('fs-animate', animating);
                    if (!sameRect(r, lastRect) || !ring || !ring.classList.contains('fs-visible')) {
                        placeRing(r);
                        lastRect = { left: r.left, top: r.top, width: r.width, height: r.height };
                    }
                } else {
                    hideRing();
                }
            } else {
                // Elemento rimosso dal re-render di Vue: recupera il più vicino all'ultima posizione
                hideRing();
                if (!lostSince) lostSince = now();
                if (now() - lostSince > 250) {
                    lostSince = 0;
                    var lr = lastRect;
                    current = null;
                    if (lr) focusNearCss(lr.left + lr.width / 2, lr.top + lr.height / 2, true);
                    else scheduleInitialFocus();
                }
            }
        } else {
            hideRing();
        }
        requestAnimationFrame(tick);
    }

    // ---------------------------------------------------------------- raccolta elementi
    function observeDom() {
        if (!window.MutationObserver) return;
        new MutationObserver(function (muts) {
            for (var i = 0; i < muts.length; i++) {
                if (muts[i].type === 'childList') { domDirty = true; return; }
            }
        }).observe(document.documentElement, { childList: true, subtree: true });
    }

    function scanPointerElements() {
        var out = [];
        if (!document.body) return out;
        var nodes = document.body.querySelectorAll(POINTER_SCAN_SEL);
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (el.id === 'fs-focus-ring') continue;
            // Le frecce dei caroselli sono gestite automaticamente da pageSlider
            if (el.matches(NEXT_HANDLE_SEL) || el.matches(PREV_HANDLE_SEL)) continue;
            if (window.getComputedStyle(el).cursor !== 'pointer') continue;
            var p = el.parentElement;
            if (p && window.getComputedStyle(p).cursor === 'pointer') continue; // cursor è ereditato
            if (el.closest('a[href], button, select, textarea, input, [role="button"]')) continue;
            out.push(el);
        }
        return out;
    }

    function getPointerElements() {
        var t = now();
        if (domDirty || t - lastScan > 2000) {
            pointerCache = scanPointerElements();
            domDirty = false;
            lastScan = t;
        }
        return pointerCache;
    }

    function isVisibleStyle(el) {
        if (typeof el.checkVisibility === 'function') {
            return el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true });
        }
        var cs = window.getComputedStyle(el);
        return cs.display !== 'none' && cs.visibility !== 'hidden' && cs.opacity !== '0';
    }

    function fixedChecker() {
        var memo = new Map();
        return function inFixed(el) {
            var chain = [];
            var n = el;
            var res = false;
            while (n && n !== document.body && n !== document.documentElement) {
                if (memo.has(n)) { res = memo.get(n); break; }
                chain.push(n);
                var pos = window.getComputedStyle(n).position;
                if (pos === 'fixed' || pos === 'sticky') { res = true; break; }
                n = n.parentElement;
            }
            for (var i = 0; i < chain.length; i++) memo.set(chain[i], res);
            return res;
        };
    }

    // Rileva sidebar / modali / backdrop a schermo intero: il focus resta intrappolato al loro interno
    function detectOverlayRoots() {
        var vw = window.innerWidth, vh = window.innerHeight;
        var hit = document.elementFromPoint(vw * 0.6, vh * 0.5);
        var roots = [];
        var big = false;
        var n = hit;
        while (n && n !== document.body && n !== document.documentElement) {
            var r = n.getBoundingClientRect();
            if (r.width * r.height >= vw * vh * 0.5) big = true;
            // Un livello fixed che contiene un elemento a tutto schermo (backdrop) è un overlay aperto
            if (big && window.getComputedStyle(n).position === 'fixed') roots.push(n);
            n = n.parentElement;
        }
        return roots;
    }

    function collectCandidates() {
        var vw = window.innerWidth, vh = window.innerHeight;
        var seen = new Set();
        var raw = [];
        var interactive = document.querySelectorAll(INTERACTIVE_SEL);
        var i;
        for (i = 0; i < interactive.length; i++) {
            if (!seen.has(interactive[i])) { seen.add(interactive[i]); raw.push(interactive[i]); }
        }
        var ptr = getPointerElements();
        for (i = 0; i < ptr.length; i++) {
            if (!seen.has(ptr[i])) { seen.add(ptr[i]); raw.push(ptr[i]); }
        }

        var inFixed = fixedChecker();
        var visible = [];
        for (i = 0; i < raw.length; i++) {
            var el = raw[i];
            if (!el.isConnected || el.disabled || el.id === 'fs-focus-ring') continue;
            if (el.tagName === 'IFRAME') continue;
            if (el.matches(NEXT_HANDLE_SEL) || el.matches(PREV_HANDLE_SEL)) continue;
            var r = el.getBoundingClientRect();
            if (r.width < 8 || r.height < 8) continue;
            // Orizzontalmente deve essere visibile per buona parte (esclude sidebar off-canvas e card fuori carosello)
            var visW = Math.min(r.right, vw) - Math.max(r.left, 0);
            if (visW < Math.min(r.width * 0.6, vw * 0.5)) continue;
            // Elementi fixed fuori dallo schermo in verticale non sono raggiungibili con lo scroll
            if ((r.bottom <= 0 || r.top >= vh) && inFixed(el)) continue;
            if (!isVisibleStyle(el)) continue;
            visible.push(el);
        }

        // Annidamenti: un wrapper con 1 solo figlio interattivo vince sul figlio,
        // un wrapper con più figli interattivi (es. barra di pulsanti) viene scartato.
        var set = new Set(visible);
        var childCount = new Map();
        var parentOf = new Map();
        for (i = 0; i < visible.length; i++) {
            var p = visible[i].parentElement;
            while (p && !set.has(p)) p = p.parentElement;
            if (p) {
                parentOf.set(visible[i], p);
                childCount.set(p, (childCount.get(p) || 0) + 1);
            }
        }
        var result = [];
        for (i = 0; i < visible.length; i++) {
            var e = visible[i];
            if ((childCount.get(e) || 0) >= 2) continue;
            var anc = parentOf.get(e);
            var drop = false;
            while (anc) {
                if ((childCount.get(anc) || 0) < 2) { drop = true; break; }
                anc = parentOf.get(anc);
            }
            if (!drop) result.push(e);
        }

        var roots = detectOverlayRoots();
        for (i = 0; i < roots.length; i++) {
            var root = roots[i];
            var inside = result.filter(function (x) { return root.contains(x); });
            if (inside.length) return inside;
        }
        return result;
    }

    // ---------------------------------------------------------------- algoritmo spaziale
    function overlap(a1, a2, b1, b2) {
        return Math.min(a2, b2) - Math.max(a1, b1);
    }

    function gap(a1, a2, b1, b2) {
        if (overlap(a1, a2, b1, b2) > 0) return 0;
        return b2 < a1 ? a1 - b2 : b1 - a2;
    }

    function findNext(direction, cands, cur) {
        var cx = cur.left + cur.width / 2, cy = cur.top + cur.height / 2;
        var vertical = direction === 'ArrowUp' || direction === 'ArrowDown';
        var best = null, bestScore = Infinity;
        var fallback = null, fallbackScore = Infinity;
        var tol = 4;

        for (var i = 0; i < cands.length; i++) {
            var el = cands[i];
            if (el === current || (current && (el.contains(current) || current.contains(el)))) continue;
            var r = el.getBoundingClientRect();
            var ex = r.left + r.width / 2, ey = r.top + r.height / 2;
            var primary, orth, inDir;

            if (direction === 'ArrowUp') {
                inDir = r.bottom <= cur.top + tol || ey < cy - cur.height / 2;
                primary = Math.max(0, cur.top - r.bottom);
            } else if (direction === 'ArrowDown') {
                inDir = r.top >= cur.bottom - tol || ey > cy + cur.height / 2;
                primary = Math.max(0, r.top - cur.bottom);
            } else if (direction === 'ArrowLeft') {
                inDir = r.right <= cur.left + tol || ex < cx - cur.width / 2;
                primary = Math.max(0, cur.left - r.right);
            } else {
                inDir = r.left >= cur.right - tol || ex > cx + cur.width / 2;
                primary = Math.max(0, r.left - cur.right);
            }
            if (!inDir) continue;

            if (vertical) {
                orth = gap(cur.left, cur.right, r.left, r.right);
                // Priorità alla riga successiva, poi all'elemento più allineato
                var s = primary * 10 + orth * 1.5 + Math.abs(ex - cx) * 0.15;
                if (s < bestScore) { bestScore = s; best = el; }
            } else {
                var sameRow = overlap(cur.top, cur.bottom, r.top, r.bottom) > Math.min(cur.height, r.height) * 0.3;
                orth = gap(cur.top, cur.bottom, r.top, r.bottom);
                if (sameRow) {
                    var s2 = primary + Math.abs(ey - cy) * 0.5;
                    if (s2 < bestScore) { bestScore = s2; best = el; }
                } else if (orth < Math.max(24, cur.height * 0.75)) {
                    var s3 = primary + orth * 6;
                    if (s3 < fallbackScore) { fallbackScore = s3; fallback = el; }
                }
            }
        }
        return best || fallback;
    }

    function readingOrder(a, b) {
        var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
        if (Math.abs(ra.top - rb.top) > 10) return ra.top - rb.top;
        return ra.left - rb.left;
    }

    // ---------------------------------------------------------------- scroll
    function scrollParent(el, axis) {
        var n = el.parentElement;
        while (n && n !== document.body && n !== document.documentElement) {
            var cs = window.getComputedStyle(n);
            var ov = axis === 'y' ? cs.overflowY : cs.overflowX;
            if ((ov === 'auto' || ov === 'scroll') &&
                (axis === 'y' ? n.scrollHeight > n.clientHeight + 4 : n.scrollWidth > n.clientWidth + 4)) {
                return n;
            }
            n = n.parentElement;
        }
        return null;
    }

    function fixedHeaderBottom() {
        var vw = window.innerWidth;
        var bottom = 0;
        var probes = [vw * 0.5, vw * 0.15, vw * 0.85];
        for (var i = 0; i < probes.length; i++) {
            var n = document.elementFromPoint(probes[i], 2);
            while (n && n !== document.body && n !== document.documentElement) {
                var pos = window.getComputedStyle(n).position;
                if (pos === 'fixed' || pos === 'sticky') {
                    var r = n.getBoundingClientRect();
                    if (r.top <= 2 && r.height < window.innerHeight * 0.4) bottom = Math.max(bottom, r.bottom);
                    break;
                }
                n = n.parentElement;
            }
        }
        return bottom;
    }

    function ensureVisible(el, fast) {
        var behavior = fast ? 'auto' : 'smooth';
        // Solo container realmente scrollabili (mai overflow:hidden, che romperebbe il layout del sito)
        var sx = scrollParent(el, 'x');
        if (sx) {
            var pr = sx.getBoundingClientRect(), er = el.getBoundingClientRect();
            if (er.left < pr.left || er.right > pr.right) {
                sx.scrollBy({ left: (er.left + er.width / 2) - (pr.left + pr.width / 2), behavior: behavior });
            }
        }

        var sy = scrollParent(el, 'y');
        var r = el.getBoundingClientRect();
        var topLimit, bottomLimit;
        if (sy) {
            var b = sy.getBoundingClientRect();
            topLimit = b.top + 8;
            bottomLimit = b.bottom - 8;
        } else {
            topLimit = fixedHeaderBottom() + 12;
            bottomLimit = window.innerHeight - 16;
        }
        var delta = 0;
        if (r.height > bottomLimit - topLimit) {
            delta = r.top - topLimit;
        } else if (r.top < topLimit || r.bottom > bottomLimit) {
            delta = (r.top + r.height / 2) - (topLimit + (bottomLimit - topLimit) / 2);
        }
        if (Math.abs(delta) < 2) return;
        if (sy) sy.scrollBy({ top: delta, behavior: behavior });
        else window.scrollBy({ top: delta, behavior: behavior });
    }

    // ---------------------------------------------------------------- focus
    function setFocus(el, fast) {
        if (!el) return;
        if (current && current !== el) {
            current.classList.remove('tv-active-focus');
            if (isEditable(current) && document.activeElement === current) current.blur();
        }
        current = el;
        el.classList.add('tv-active-focus');
        ringAnimUntil = now() + (fast ? 90 : 200);
        ensureVisible(el, fast);
    }

    function focusNearCss(x, y, fast) {
        var cands = collectCandidates();
        var best = null, bestD = Infinity;
        for (var i = 0; i < cands.length; i++) {
            var r = cands[i].getBoundingClientRect();
            var dx = Math.max(r.left - x, 0, x - r.right);
            var dy = Math.max(r.top - y, 0, y - r.bottom);
            var d = Math.hypot(dx, dy);
            if (d < bestD) { bestD = d; best = cands[i]; }
        }
        if (best) setFocus(best, fast);
        return best;
    }

    function pickInitial(cands) {
        var header = fixedHeaderBottom();
        var preferred = cands.filter(function (el) { return el.matches(PREFERRED_START_SEL); }).sort(readingOrder);
        for (var i = 0; i < preferred.length; i++) {
            var pr = preferred[i].getBoundingClientRect();
            if (pr.top >= header && pr.top < window.innerHeight) return preferred[i];
        }
        var inView = cands.filter(function (el) {
            var r = el.getBoundingClientRect();
            return r.top >= header && r.bottom <= window.innerHeight;
        }).sort(readingOrder);
        if (inView.length) return inView[0];
        return cands.slice().sort(readingOrder)[0] || null;
    }

    function scheduleInitialFocus(attempt) {
        attempt = attempt || 0;
        clearTimeout(initTimer);
        initTimer = setTimeout(function () {
            if (mouseMode || isPlayerPage()) return;
            if (current && current.isConnected) return;
            var cands = collectCandidates();
            var el = pickInitial(cands);
            if (el) {
                current = null;
                setFocus(el, true);
                closePromoBanner();
            } else if (attempt < 12) {
                scheduleInitialFocus(attempt + 1);
            }
        }, attempt === 0 ? 400 : 500);
    }

    // ---------------------------------------------------------------- caroselli
    function findSliderHandle(el, direction) {
        var sel = direction === 'ArrowRight' ? NEXT_HANDLE_SEL : PREV_HANDLE_SEL;
        var er = el.getBoundingClientRect();
        var n = el.parentElement;
        for (var depth = 0; n && n !== document.body && depth < 8; depth++, n = n.parentElement) {
            var handles = n.querySelectorAll(sel);
            for (var i = 0; i < handles.length; i++) {
                var h = handles[i];
                var r = h.getBoundingClientRect();
                // La freccia deve appartenere alla stessa riga dell'elemento selezionato
                if (r.width > 0 && r.height > 0 && overlap(er.top, er.bottom, r.top, r.bottom) > 0 && isVisibleStyle(h)) {
                    return { handle: h, container: n };
                }
            }
        }
        return null;
    }

    function pageSlider(direction) {
        var found = findSliderHandle(current, direction);
        if (!found) {
            var sx = scrollParent(current, 'x');
            if (!sx) return false;
            sx.scrollBy({ left: (direction === 'ArrowRight' ? 1 : -1) * sx.clientWidth * 0.8, behavior: 'smooth' });
            found = { container: sx };
        } else {
            syntheticClick(found.handle);
        }

        var container = found.container;
        var from = current;
        busyUntil = now() + 1200;
        var lastPos = null, stable = 0, started = now();
        (function waitForSlide() {
            var r = from.isConnected ? from.getBoundingClientRect() : null;
            var pos = r ? r.left : 0;
            if (lastPos !== null && Math.abs(pos - lastPos) < 0.5) stable++; else stable = 0;
            lastPos = pos;
            if ((stable >= 3 && now() - started > 350) || now() - started > 1100) {
                busyUntil = 0;
                var cands = collectCandidates().filter(function (c) { return container.contains(c); });
                var cur = r || lastRect;
                var next = cur ? findNext(direction, cands, cur) : null;
                if (!next) {
                    var sorted = cands.sort(readingOrder);
                    next = direction === 'ArrowRight' ? sorted[0] : sorted[sorted.length - 1];
                }
                if (next) setFocus(next, false);
                return;
            }
            setTimeout(waitForSlide, 50);
        })();
        return true;
    }

    // ---------------------------------------------------------------- click
    function syntheticClick(el) {
        var r = el.getBoundingClientRect();
        var opts = {
            bubbles: true, cancelable: true, view: window, button: 0,
            clientX: r.left + r.width / 2, clientY: r.top + r.height / 2
        };
        try {
            if (window.PointerEvent) el.dispatchEvent(new PointerEvent('pointerdown', opts));
            el.dispatchEvent(new MouseEvent('mousedown', opts));
            if (window.PointerEvent) el.dispatchEvent(new PointerEvent('pointerup', opts));
            el.dispatchEvent(new MouseEvent('mouseup', opts));
            // UN solo click (prima veniva emesso due volte: i toggle si aprivano e richiudevano subito)
            el.dispatchEvent(new MouseEvent('click', opts));
        } catch (e) {
            if (typeof el.click === 'function') el.click();
        }
    }

    // Tap nativo: eventi "trusted" (necessari per fullscreen, autoplay e apertura tastiera)
    function nativeTap(el) {
        var b = bridge();
        if (!b || !b.tapAt) return false;
        var r = el.getBoundingClientRect();
        var x = r.left + r.width / 2, y = r.top + r.height / 2;
        if (x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) return false;
        var hit = document.elementFromPoint(x, y);
        if (!hit || !(hit === el || el.contains(hit))) return false;
        b.tapAt(x, y, window.innerWidth);
        return true;
    }

    window.tvClick = function () {
        if (!current || !current.isConnected) {
            current = null;
            scheduleInitialFocus();
            return;
        }
        var el = current;
        if (isEditable(el)) {
            ensureVisible(el, true);
            el.focus();
            if (!nativeTap(el)) {
                var b = bridge();
                if (b && b.onInputFocus) b.onInputFocus(true);
            }
            return;
        }
        if (!nativeTap(el)) syntheticClick(el);
        // Se il click apre un menu/modale, sposta subito il focus al suo interno
        setTimeout(adoptOverlayFocus, 450);
    };

    function adoptOverlayFocus() {
        var roots = detectOverlayRoots();
        if (!roots.length || (current && current.isConnected && roots[0].contains(current))) return;
        var inside = collectCandidates().filter(function (c) { return roots[0].contains(c); });
        if (inside.length) {
            focusBeforeOverlay = current;
            setFocus(inside.sort(readingOrder)[0], true);
        }
    }
    var focusBeforeOverlay = null;

    // Riapre la tastiera sul campo attivo (OK premuto con tastiera chiusa)
    window.tvTapActiveInput = function () {
        var el = document.activeElement;
        if (!isEditable(el)) return false;
        if (!nativeTap(el)) {
            var b = bridge();
            if (b && b.onInputFocus) b.onInputFocus(true);
        }
        return true;
    };

    window.tvBlurInput = function () {
        var el = document.activeElement;
        if (isEditable(el)) {
            el.blur();
            if (current !== el && el.isConnected) {
                current = null;
                setFocus(el, true);
            }
        }
    };

    // ---------------------------------------------------------------- API per Android
    window.tvNavigate = function (direction, fast) {
        if (mouseMode) return;

        if (isPlayerPage()) {
            if (window.fsPlayer) {
                if (direction === 'ArrowRight') window.fsPlayer('seek', 10);
                else if (direction === 'ArrowLeft') window.fsPlayer('seek', -10);
                else if (direction === 'Enter') window.fsPlayer('toggle');
                else window.fsPlayer('activity');
            }
            return;
        }

        if (direction === 'Enter') {
            window.tvClick();
            return;
        }
        if (now() < busyUntil) return;

        var cands = collectCandidates();
        if (!current || !current.isConnected) {
            current = null;
            var first = pickInitial(cands);
            if (first) setFocus(first, fast);
            return;
        }

        var cur = current.getBoundingClientRect();
        var next = findNext(direction, cands, cur);
        if (next) {
            setFocus(next, fast);
            return;
        }

        if (direction === 'ArrowLeft' || direction === 'ArrowRight') {
            pageSlider(direction);
        } else {
            // Nessun elemento: scorri e riprova (contenuti caricati in lazy-load)
            window.scrollBy({ top: direction === 'ArrowUp' ? -300 : 300, behavior: fast ? 'auto' : 'smooth' });
            setTimeout(function () {
                var again = findNext(direction, collectCandidates(), current.getBoundingClientRect());
                if (again) setFocus(again, fast);
            }, fast ? 60 : 250);
        }
    };

    window.tvClearFocus = function () {
        mouseMode = true;
        hideRing();
        if (current) {
            current.classList.remove('tv-active-focus');
            if (isEditable(current) && document.activeElement === current) current.blur();
        }
    };

    // Coordinate in pixel della View Android: le convertiamo in pixel CSS
    window.tvFocusNear = function (px, py, viewWidth) {
        mouseMode = false;
        var f = viewWidth > 0 ? window.innerWidth / viewWidth : 1 / (window.devicePixelRatio || 1);
        current = null;
        if (isPlayerPage()) return;
        if (!focusNearCss(px * f, py * f, true)) scheduleInitialFocus();
    };

    // Scorrimento al bordo in modalità mouse (coordinate View Android)
    window.fsScrollAt = function (px, py, dx, dy, viewWidth) {
        var f = viewWidth > 0 ? window.innerWidth / viewWidth : 1 / (window.devicePixelRatio || 1);
        var el = document.elementFromPoint(px * f, py * f);
        var target = null;
        while (el && el !== document.body && el !== document.documentElement) {
            var cs = window.getComputedStyle(el);
            if ((dy && (cs.overflowY === 'auto' || cs.overflowY === 'scroll') && el.scrollHeight > el.clientHeight + 4) ||
                (dx && (cs.overflowX === 'auto' || cs.overflowX === 'scroll') && el.scrollWidth > el.clientWidth + 4)) {
                target = el;
                break;
            }
            el = el.parentElement;
        }
        (target || window).scrollBy(dx * f, dy * f);
    };

    // BACK: chiude overlay/menu/dropdown prima di tornare indietro nella cronologia
    window.tvBack = function () {
        if (document.fullscreenElement && document.exitFullscreen) {
            document.exitFullscreen();
            return true;
        }
        var openDrop = Array.prototype.find.call(
            document.querySelectorAll('.dropdown-wrap, .dropdown-menu, [role="listbox"], [role="menu"]'),
            function (d) { var r = d.getBoundingClientRect(); return r.width > 0 && r.height > 0 && isVisibleStyle(d); });
        if (openDrop) {
            var trigger = openDrop.parentElement && openDrop.parentElement.querySelector('.dropdown-trigger, [aria-expanded="true"]');
            if (trigger) {
                var focusTarget = trigger.querySelector('button') || trigger;
                syntheticClick(focusTarget);
                setTimeout(function () { setFocus(focusTarget, true); }, 50);
            } else {
                document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
            }
            return true;
        }
        var roots = detectOverlayRoots();
        if (roots.length) {
            var root = roots[0];
            var closer = root.querySelector('[class*="backdrop"], [class*="close"], [aria-label*="close" i], [aria-label*="chiudi" i]');
            if (closer) syntheticClick(closer);
            else document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }));
            var restore = focusBeforeOverlay;
            focusBeforeOverlay = null;
            setTimeout(function () {
                if (restore && restore.isConnected) setFocus(restore, true);
                else { current = null; scheduleInitialFocus(); }
            }, 350);
            return true;
        }
        return false;
    };

    window.tvSetMouseMode = function (on) {
        mouseMode = !!on;
        if (mouseMode) hideRing();
    };

    // ---------------------------------------------------------------- tastiera virtuale
    document.addEventListener('focusin', function (e) {
        if (isEditable(e.target)) {
            var b = bridge();
            if (b && b.onInputFocus) b.onInputFocus(true);
        }
    }, true);

    // Debounce: un blur transitorio (re-render Vue, cambio di input) non deve chiudere la tastiera
    document.addEventListener('focusout', function (e) {
        if (!isEditable(e.target)) return;
        setTimeout(function () {
            if (!isEditable(document.activeElement)) {
                var b = bridge();
                if (b && b.onInputFocus) b.onInputFocus(false);
            }
        }, 300);
    }, true);

    // ---------------------------------------------------------------- navigazione SPA (Inertia)
    function onRouteChange() {
        setTimeout(function () {
            if (!current || !current.isConnected) {
                current = null;
                lastRect = null;
                hideRing();
                scheduleInitialFocus();
            }
        }, 300);
    }
    ['pushState', 'replaceState'].forEach(function (m) {
        var orig = history[m];
        history[m] = function () {
            var res = orig.apply(this, arguments);
            onRouteChange();
            return res;
        };
    });
    window.addEventListener('popstate', onRouteChange);

    // ---------------------------------------------------------------- init
    // Chiude il banner promozionale Telegram per recuperare spazio verticale
    var promoClosed = false;
    function closePromoBanner() {
        if (promoClosed) return;
        var closeBanner = document.querySelector('header .close-banner');
        if (closeBanner) {
            promoClosed = true;
            syntheticClick(closeBanner);
        }
    }

    function init() {
        injectStyles();
        observeDom();
        scheduleInitialFocus();
        requestAnimationFrame(tick);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
    window.addEventListener('load', function () { if (!current) scheduleInitialFocus(); });

    console.log('[FreeStream TV Navigation 3.0] Inizializzato');
})();
