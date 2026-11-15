(function () {
    'use strict';

    // Agente iniettato in TUTTI i frame (anche l'iframe cross-origin del player vixcloud/JWPlayer).
    // Riceve comandi dal frame padre via postMessage e li inoltra ai sotto-frame.
    if (window.__fsPlayerAgent) return;
    window.__fsPlayerAgent = true;

    var RESUME_STORAGE_KEY = '__fs_resume_v1';
    var MAX_RESUME_ENTRIES = 80;
    var customWatchKey = null;
    var restoredForKey = null;
    var osdEl = null;
    var osdTimer = null;
    var lastSeekAt = 0;
    var lastSeekDir = 0;
    var seekStreak = 0;
    var cumulativeSeek = 0;

    function activeWatchKey() {
        return customWatchKey || (location.pathname + location.search);
    }

    function formatTime(totalSec) {
        var s = Math.max(0, Math.floor(totalSec || 0));
        var h = Math.floor(s / 3600);
        var m = Math.floor((s % 3600) / 60);
        var sec = s % 60;
        var mm = m < 10 ? '0' + m : '' + m;
        var ss = sec < 10 ? '0' + sec : '' + sec;
        return h > 0 ? (h + ':' + mm + ':' + ss) : (mm + ':' + ss);
    }

    function showPlayerOsd(text) {
        try {
            var host = document.fullscreenElement || document.body || document.documentElement;
            if (!host) return;
            if (!osdEl) {
                osdEl = document.createElement('div');
                osdEl.id = 'fs-player-osd';
                osdEl.style.cssText =
                    'position:fixed;top:24px;right:28px;z-index:2147483647;' +
                    'background:rgba(13,17,23,0.88);color:#FFFFFF;' +
                    'border:2px solid #00D2FF;border-radius:12px;' +
                    'padding:10px 20px;font-family:sans-serif;font-size:20px;' +
                    'font-weight:700;letter-spacing:0.3px;pointer-events:none;' +
                    'box-shadow:0 6px 24px rgba(0,0,0,0.65);' +
                    'opacity:0;transition:opacity 0.18s ease;';
            }
            if (osdEl.parentNode !== host) {
                host.appendChild(osdEl);
            }
            osdEl.textContent = text;
            osdEl.style.opacity = '1';
            clearTimeout(osdTimer);
            osdTimer = setTimeout(function () {
                if (osdEl) osdEl.style.opacity = '0';
            }, 1200);
        } catch (e) { /* ignore */ }
    }

    function readResumeMap() {
        try {
            var raw = localStorage.getItem(RESUME_STORAGE_KEY);
            if (!raw) return {};
            var parsed = JSON.parse(raw);
            return (parsed && typeof parsed === 'object') ? parsed : {};
        } catch (e) {
            return {};
        }
    }

    function writeResumeMap(map) {
        try {
            var keys = Object.keys(map);
            if (keys.length > MAX_RESUME_ENTRIES) {
                keys.sort(function (a, b) {
                    return ((map[a] && map[a].ts) || 0) - ((map[b] && map[b].ts) || 0);
                });
                var removeCount = keys.length - MAX_RESUME_ENTRIES;
                for (var i = 0; i < removeCount; i++) {
                    delete map[keys[i]];
                }
            }
            localStorage.setItem(RESUME_STORAGE_KEY, JSON.stringify(map));
        } catch (e) { /* storage disabilitato o quota superata */ }
    }

    function getSavedPosition(key) {
        var map = readResumeMap();
        var entry = map[key];
        return (entry && typeof entry.t === 'number' && isFinite(entry.t)) ? entry.t : 0;
    }

    function savePosition(key, pos, dur) {
        if (!key || !isFinite(pos) || !isFinite(dur) || dur <= 60) return;
        var map = readResumeMap();
        if (pos > 15 && pos < dur - 30) {
            map[key] = { t: Math.floor(pos), ts: Date.now() };
            writeResumeMap(map);
        } else if (pos >= dur - 30 && map[key]) {
            delete map[key];
            writeResumeMap(map);
        }
    }

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

    function getMediaState() {
        var p = jw();
        if (p) {
            var d = Number(p.getDuration && p.getDuration());
            var pos = Number(p.getPosition && p.getPosition());
            return { type: 'jw', player: p, pos: isFinite(pos) ? pos : 0, dur: isFinite(d) ? d : 0 };
        }
        var v = mainVideo();
        if (v) {
            return {
                type: 'video',
                player: v,
                pos: isFinite(v.currentTime) ? v.currentTime : 0,
                dur: isFinite(v.duration) ? v.duration : 0
            };
        }
        return null;
    }

    function tryAutoResume() {
        var key = activeWatchKey();
        if (!key || restoredForKey === key) return;
        var st = getMediaState();
        if (!st || !isFinite(st.dur) || st.dur <= 60) return;

        restoredForKey = key;
        var saved = getSavedPosition(key);
        if (saved > 15 && saved < st.dur - 30 && st.pos < 10) {
            if (st.type === 'jw') st.player.seek(saved);
            else st.player.currentTime = saved;
            showPlayerOsd('▶ Ripreso da ' + formatTime(saved));
        }
    }

    function saveCurrentProgress() {
        var st = getMediaState();
        if (!st) return;
        savePosition(activeWatchKey(), st.pos, st.dur);
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

    function computeSeekDelta(arg) {
        var baseDelta = (arg && typeof arg === 'object') ? Number(arg.delta || 0) : Number(arg || 0);
        if (!baseDelta) return 0;
        var dir = baseDelta >= 0 ? 1 : -1;
        var now = Date.now();
        if (now - lastSeekAt <= 650 && lastSeekDir === dir) {
            seekStreak++;
        } else {
            seekStreak = 1;
            cumulativeSeek = 0;
        }
        lastSeekAt = now;
        lastSeekDir = dir;

        var actualDelta = baseDelta;
        if (Math.abs(baseDelta) === 10) {
            var step = seekStreak <= 2 ? 10 : (seekStreak <= 5 ? 20 : 30);
            actualDelta = dir * step;
        }
        cumulativeSeek += actualDelta;
        return actualDelta;
    }

    function showSeekOsd(targetPos) {
        var sign = cumulativeSeek >= 0 ? '+' : '';
        var arrow = cumulativeSeek >= 0 ? '⏩ ' : '⏪ ';
        showPlayerOsd(arrow + sign + cumulativeSeek + 's (' + formatTime(targetPos) + ')');
    }

    function local(cmd, arg) {
        var p = jw();
        if (p) {
            if (cmd === 'activity') {
                poke();
                return true;
            }
            var state = p.getState();
            if (cmd === 'toggle') {
                if (state === 'playing' || state === 'buffering') {
                    p.pause();
                    showPlayerOsd('⏸ Pausa');
                } else {
                    p.play();
                    showPlayerOsd('▶ Riproduzione');
                }
            } else if (cmd === 'play') {
                p.play();
                showPlayerOsd('▶ Riproduzione');
            } else if (cmd === 'pause') {
                p.pause();
                showPlayerOsd('⏸ Pausa');
            } else if (cmd === 'seek') {
                var dur = p.getDuration() || Infinity;
                var delta = computeSeekDelta(arg);
                var target = Math.max(0, Math.min(dur - 1, (p.getPosition() || 0) + delta));
                p.seek(target);
                showSeekOsd(target);
            } else if (cmd === 'next') {
                var nb = document.querySelector('.jw-icon-next');
                if (nb) nb.click();
            }
            poke();
            return true;
        }
        var v = mainVideo();
        if (!v) return false;
        if (cmd === 'activity') {
            poke();
            return true;
        }
        if (cmd === 'toggle') {
            if (v.paused) {
                v.play();
                showPlayerOsd('▶ Riproduzione');
            } else {
                v.pause();
                showPlayerOsd('⏸ Pausa');
            }
        } else if (cmd === 'play') {
            v.play();
            showPlayerOsd('▶ Riproduzione');
        } else if (cmd === 'pause') {
            v.pause();
            showPlayerOsd('⏸ Pausa');
        } else if (cmd === 'seek') {
            var d = isFinite(v.duration) ? v.duration : Infinity;
            var vDelta = computeSeekDelta(arg);
            var vTarget = Math.max(0, Math.min(d, v.currentTime + vDelta));
            v.currentTime = vTarget;
            showSeekOsd(vTarget);
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

    function relayWatchKey(key) {
        var frames = document.getElementsByTagName('iframe');
        for (var i = 0; i < frames.length; i++) {
            try { frames[i].contentWindow.postMessage({ __fsWatchKey: key }, '*'); } catch (e) { /* ignore */ }
        }
    }

    function handle(cmd, arg) {
        if (!local(cmd, arg)) relay(cmd, arg);
    }

    window.addEventListener('message', function (e) {
        var d = e.data;
        if (!d || typeof d !== 'object') return;
        if (e.source === window.parent && window.parent !== window) {
            if (typeof d.__fsWatchKey === 'string' && d.__fsWatchKey) {
                if (customWatchKey !== d.__fsWatchKey) {
                    customWatchKey = d.__fsWatchKey;
                    restoredForKey = null;
                    tryAutoResume();
                }
                relayWatchKey(customWatchKey);
            }
            if (d.__fsCmd) {
                handle(d.__fsCmd, d.arg);
            }
        }
    });

    // Ascoltatori per salvataggio posizione e ripristino automatico all'avvio del video
    document.addEventListener('loadedmetadata', tryAutoResume, true);
    document.addEventListener('canplay', tryAutoResume, true);
    document.addEventListener('playing', tryAutoResume, true);
    document.addEventListener('pause', saveCurrentProgress, true);
    window.addEventListener('pagehide', saveCurrentProgress);
    setInterval(function () {
        tryAutoResume();
        saveCurrentProgress();
    }, 5000);

    if (window.top === window) window.fsPlayer = handle;
})();
