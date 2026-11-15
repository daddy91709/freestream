(function () {
    'use strict';

    // Agente iniettato in TUTTI i frame (anche l'iframe cross-origin del player vixcloud/JWPlayer).
    // Riceve comandi dal frame padre via postMessage e li inoltra ai sotto-frame.
    if (window.__fsPlayerAgent) return;
    window.__fsPlayerAgent = true;

    function jw() {
        try {
            if (typeof window.jwplayer === 'function') {
                var p = window.jwplayer();
                if (p && typeof p.getState === 'function' && p.getState()) return p;
            }
        } catch (e) { /* player non ancora pronto */ }
        return null;
    }

    function mainVideo() {
        var vids = document.getElementsByTagName('video');
        var best = null, area = 0;
        for (var i = 0; i < vids.length; i++) {
            var r = vids[i].getBoundingClientRect();
            if (r.width * r.height > area) { area = r.width * r.height; best = vids[i]; }
        }
        return best;
    }

    // Mostra i controlli del player simulando attività del mouse
    function poke() {
        var target = document.querySelector('.jwplayer') || mainVideo();
        if (!target) return;
        var r = target.getBoundingClientRect();
        var opts = { bubbles: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
        try {
            if (window.PointerEvent) target.dispatchEvent(new PointerEvent('pointermove', opts));
            target.dispatchEvent(new MouseEvent('mousemove', opts));
        } catch (e) { /* ignore */ }
    }

    function local(cmd, arg) {
        var p = jw();
        if (p) {
            var state = p.getState();
            if (cmd === 'toggle') { if (state === 'playing' || state === 'buffering') p.pause(); else p.play(); }
            else if (cmd === 'play') p.play();
            else if (cmd === 'pause') p.pause();
            else if (cmd === 'seek') {
                var dur = p.getDuration() || Infinity;
                p.seek(Math.max(0, Math.min(dur - 1, (p.getPosition() || 0) + arg)));
            } else if (cmd === 'next') {
                var nb = document.querySelector('.jw-icon-next');
                if (nb) nb.click();
            }
            poke();
            return true;
        }
        var v = mainVideo();
        if (!v) return false;
        if (cmd === 'toggle') { if (v.paused) v.play(); else v.pause(); }
        else if (cmd === 'play') v.play();
        else if (cmd === 'pause') v.pause();
        else if (cmd === 'seek') {
            var d = isFinite(v.duration) ? v.duration : Infinity;
            v.currentTime = Math.max(0, Math.min(d, v.currentTime + arg));
        }
        poke();
        return true;
    }

    function relay(cmd, arg) {
        var frames = document.getElementsByTagName('iframe');
        for (var i = 0; i < frames.length; i++) {
            try { frames[i].contentWindow.postMessage({ __fsCmd: cmd, arg: arg }, '*'); } catch (e) { /* ignore */ }
        }
    }

    function handle(cmd, arg) {
        if (!local(cmd, arg)) relay(cmd, arg);
    }

    window.addEventListener('message', function (e) {
        var d = e.data;
        // Accetta comandi solo dal frame padre
        if (d && typeof d === 'object' && d.__fsCmd && e.source === window.parent && window.parent !== window) {
            handle(d.__fsCmd, d.arg);
        }
    });

    if (window.top === window) window.fsPlayer = handle;
})();
