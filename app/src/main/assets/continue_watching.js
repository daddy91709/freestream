(function () {
    'use strict';

    if (window.top !== window || window.__fsContinueWatching) return;
    window.__fsContinueWatching = true;

    var META_STORAGE_KEY = '__fs_title_meta_v1';
    var MAX_META_ENTRIES = 200;
    var FILENAME_RE = /^[A-Za-z0-9._-]+$/;
    var WATCH_PATH_RE = /\/watch\/(\d+)/;
    var TITLE_PATH_RE = /\/titles\/(\d+)(?:-([^/?#]+))?/;

    var entries = [];
    var metaCache = loadMetaCache();
    var lastBackfillSig = '';
    var lastRenderedSig = null;
    var renderTimer = null;
    var mutTimer = null;
    var resizeTimer = null;
    var mountRetries = 0;

    function bridge() {
        return window.AndroidBridge;
    }

    function sendBridgeMessage(payload, legacyMethod, legacyArg) {
        var b = bridge();
        if (!b) return false;
        if (typeof b.postMessage === 'function') {
            b.postMessage(JSON.stringify(payload));
            return true;
        }
        if (legacyMethod && typeof b[legacyMethod] === 'function') {
            if (legacyArg !== undefined) b[legacyMethod](legacyArg);
            else b[legacyMethod]();
            return true;
        }
        return false;
    }

    function sendSyncRequest() {
        return sendBridgeMessage({ type: 'cwRequestSync' }, 'cwRequestSync');
    }

    function sendProgress(payload) {
        var msg = {};
        for (var k in payload) {
            if (Object.prototype.hasOwnProperty.call(payload, k)) msg[k] = payload[k];
        }
        msg.type = 'cwProgress';
        return sendBridgeMessage(msg, 'cwSaveProgress', JSON.stringify(msg));
    }

    function sendMetaUpdate(payload) {
        var msg = {};
        for (var k in payload) {
            if (Object.prototype.hasOwnProperty.call(payload, k)) msg[k] = payload[k];
        }
        msg.type = 'cwUpdateMeta';
        return sendBridgeMessage(msg, 'cwUpdateMeta', JSON.stringify(msg));
    }

    function loadMetaCache() {
        try {
            var raw = sessionStorage.getItem(META_STORAGE_KEY);
            if (!raw) return {};
            var parsed = JSON.parse(raw);
            return (parsed && typeof parsed === 'object') ? parsed : {};
        } catch (e) {
            return {};
        }
    }

    function saveMetaCache() {
        try {
            var keys = Object.keys(metaCache);
            if (keys.length > MAX_META_ENTRIES) {
                keys.sort(function (a, b) {
                    return ((metaCache[a] && metaCache[a].ts) || 0) - ((metaCache[b] && metaCache[b].ts) || 0);
                });
                var removeCount = keys.length - MAX_META_ENTRIES;
                for (var i = 0; i < removeCount; i++) {
                    delete metaCache[keys[i]];
                }
            }
            sessionStorage.setItem(META_STORAGE_KEY, JSON.stringify(metaCache));
        } catch (e) { /* ignore */ }
    }

    function sanitizeFilename(raw) {
        if (typeof raw !== 'string') return '';
        var s = raw.trim();
        if (!s || s.length > 128 || s.indexOf('..') !== -1) return '';
        return FILENAME_RE.test(s) ? s : '';
    }

    function extractFilenameFromUrl(src) {
        if (typeof src !== 'string') return '';
        var m = src.match(/\/images\/([A-Za-z0-9._-]+)(?:[?#]|$)/);
        return m ? sanitizeFilename(m[1]) : '';
    }

    function extractImageFilename(images, preferredTypes) {
        if (!Array.isArray(images)) return '';
        for (var p = 0; p < preferredTypes.length; p++) {
            var wanted = preferredTypes[p];
            for (var i = 0; i < images.length; i++) {
                var img = images[i];
                if (img && img.type === wanted) {
                    var fn = sanitizeFilename(img.filename);
                    if (fn) return fn;
                }
            }
        }
        return '';
    }

    function getInertiaPage() {
        try {
            if (history.state && typeof history.state === 'object' && history.state.props) {
                return history.state;
            }
        } catch (e) { /* ignore */ }
        try {
            var app = document.getElementById('app');
            var raw = app && app.getAttribute('data-page');
            if (raw) {
                var parsed = JSON.parse(raw);
                if (parsed && typeof parsed === 'object') return parsed;
            }
        } catch (e) { /* ignore */ }
        return null;
    }

    function upsertMetaItem(item, locale) {
        if (!item || !item.id) return false;
        var id = String(Number(item.id));
        if (!id || id === 'NaN' || Number(id) <= 0) return false;

        var prev = metaCache[id] || {};
        var cover = extractImageFilename(item.images, ['cover', 'background', 'cover_mobile']) || prev.coverFilename || '';
        var poster = extractImageFilename(item.images, ['poster', 'cover_mobile', 'cover']) || prev.posterFilename || '';
        var slug = (typeof item.slug === 'string' && item.slug.trim()) ? item.slug.trim() : (prev.slug || '');
        var name = (typeof item.name === 'string' && item.name.trim()) ? item.name.trim() : (prev.name || '');
        var mediaType = (item.type === 'tv' || item.type === 'movie') ? item.type : (prev.mediaType || '');
        var subIta = typeof item.sub_ita !== 'undefined' ? Boolean(item.sub_ita) : Boolean(prev.subIta);
        var loc = (typeof locale === 'string' && locale.trim()) ? locale.trim() : (prev.locale || 'it');
        var titlePath = (slug ? ('/' + loc + '/titles/' + id + '-' + slug) : (prev.titlePath || ''));

        var changed = cover !== (prev.coverFilename || '') ||
            poster !== (prev.posterFilename || '') ||
            slug !== (prev.slug || '') ||
            name !== (prev.name || '') ||
            mediaType !== (prev.mediaType || '') ||
            subIta !== Boolean(prev.subIta) ||
            titlePath !== (prev.titlePath || '');

        metaCache[id] = {
            id: Number(id),
            slug: slug,
            name: name,
            mediaType: mediaType,
            locale: loc,
            titlePath: titlePath,
            coverFilename: cover,
            posterFilename: poster,
            subIta: subIta,
            ts: Date.now()
        };
        return changed;
    }

    function backfillMissingEntryMeta() {
        if (!entries || !entries.length) return;
        for (var i = 0; i < entries.length; i++) {
            var e = entries[i];
            if (!e || !e.titleId) continue;
            if (e.coverFilename && e.posterFilename) continue;
            var meta = metaCache[String(e.titleId)];
            if (!meta) continue;
            var needCover = !e.coverFilename && meta.coverFilename;
            var needPoster = !e.posterFilename && meta.posterFilename;
            if (needCover || needPoster) {
                var sig = e.titleId + ':' + (meta.coverFilename || '') + ':' + (meta.posterFilename || '');
                if (sig !== lastBackfillSig) {
                    lastBackfillSig = sig;
                    sendMetaUpdate({
                        titleId: e.titleId,
                        coverFilename: meta.coverFilename || '',
                        posterFilename: meta.posterFilename || '',
                        titlePath: meta.titlePath || '',
                        name: meta.name || '',
                        slug: meta.slug || '',
                        subIta: !!meta.subIta
                    });
                }
            }
        }
    }

    function harvestInertiaPage() {
        var page = getInertiaPage();
        if (!page || !page.props) return;
        var props = page.props;
        var locale = (typeof props.locale === 'string' && props.locale.trim()) ? props.locale.trim() : 'it';
        var changed = false;

        if (props.title && typeof props.title === 'object') {
            if (upsertMetaItem(props.title, locale)) changed = true;
        }
        if (Array.isArray(props.sliders)) {
            for (var i = 0; i < props.sliders.length; i++) {
                var sl = props.sliders[i];
                if (sl && Array.isArray(sl.titles)) {
                    for (var j = 0; j < sl.titles.length; j++) {
                        if (upsertMetaItem(sl.titles[j], locale)) changed = true;
                    }
                }
            }
        }
        var list = Array.isArray(props.titles)
            ? props.titles
            : (props.titles && Array.isArray(props.titles.data) ? props.titles.data : null);
        if (list) {
            for (var k = 0; k < list.length; k++) {
                if (upsertMetaItem(list[k], locale)) changed = true;
            }
        }

        if (changed) {
            saveMetaCache();
        }
        backfillMissingEntryMeta();
    }

    function harvestCardElement(anchor) {
        if (!anchor || !anchor.getAttribute) return false;
        if (anchor.classList && anchor.classList.contains('fs-cw-card')) return false;
        var href = anchor.getAttribute('href') || '';
        var m = href.match(TITLE_PATH_RE);
        if (!m) return false;

        var id = Number(m[1]);
        if (!id || id <= 0) return false;
        var slug = m[2] || '';

        var img = anchor.querySelector('img[src*="/images/"]');
        var fn = img ? extractFilenameFromUrl(img.getAttribute('src') || img.src || '') : '';
        var prev = metaCache[String(id)] || {};

        var isPoster = img && (
            img.classList.contains('poster-image') ||
            !!img.closest('.box-7x10, .title-boxart, .poster')
        );
        var isCover = img && (
            img.classList.contains('cover-image') ||
            !!img.closest('.box-16x9, .boxart')
        );

        var cover = isCover ? fn : (prev.coverFilename || '');
        var poster = isPoster ? fn : (prev.posterFilename || '');
        if (fn && !cover && !poster) {
            cover = fn;
        }
        var alt = img && typeof img.alt === 'string' ? img.alt.trim() : '';
        var name = alt || prev.name || '';
        var locMatch = href.match(/^\/([a-z]{2})\//i);
        var locale = locMatch ? locMatch[1].toLowerCase() : (prev.locale || 'it');
        var titlePath = slug ? ('/' + locale + '/titles/' + id + '-' + slug) : (prev.titlePath || '');

        var changed = cover !== (prev.coverFilename || '') ||
            poster !== (prev.posterFilename || '') ||
            (name && name !== (prev.name || '')) ||
            (slug && slug !== (prev.slug || ''));

        metaCache[String(id)] = {
            id: id,
            slug: slug || prev.slug || '',
            name: name,
            mediaType: prev.mediaType || '',
            locale: locale,
            titlePath: titlePath,
            coverFilename: cover,
            posterFilename: poster,
            subIta: Boolean(prev.subIta),
            ts: Date.now()
        };
        return changed;
    }

    function harvestDomCards() {
        if (!document.body) return;
        var anchors = document.body.querySelectorAll('a[href*="/titles/"]');
        var changed = false;
        for (var i = 0; i < anchors.length; i++) {
            if (harvestCardElement(anchors[i])) changed = true;
        }
        if (changed) {
            saveMetaCache();
            backfillMissingEntryMeta();
        }
    }

    function parseWatchRoute(pathPlusSearch) {
        var raw = pathPlusSearch || (location.pathname + location.search);
        var qIdx = raw.indexOf('?');
        var pathname = qIdx >= 0 ? raw.slice(0, qIdx) : raw;
        var search = qIdx >= 0 ? raw.slice(qIdx) : '';
        var m = pathname.match(WATCH_PATH_RE);
        if (!m) return null;
        var titleId = Number(m[1]);
        if (!titleId || titleId <= 0) return null;
        var epMatch = search.match(/[?&]e=(\d+)/);
        var episodeId = epMatch ? Number(epMatch[1]) : null;
        return { titleId: titleId, episodeId: (episodeId && episodeId > 0) ? episodeId : null };
    }

    window.__fsGetResumePos = function (watchKey) {
        var parsed = parseWatchRoute(watchKey);
        if (!parsed) return 0;
        for (var i = 0; i < entries.length; i++) {
            var e = entries[i];
            if (!e || Number(e.titleId) !== parsed.titleId) continue;
            if (e.type === 'tv') {
                if (parsed.episodeId && e.episodeId && Number(e.episodeId) === parsed.episodeId) {
                    return Number(e.positionSec) || 0;
                }
                if (watchKey && e.watchPath && e.watchPath === watchKey) {
                    return Number(e.positionSec) || 0;
                }
                return 0;
            }
            return Number(e.positionSec) || 0;
        }
        return 0;
    };

    function handleProgressFromPlayer(p) {
        if (!p || typeof p !== 'object') return;
        var currentWatch = parseWatchRoute();
        if (!currentWatch) return;

        var currentKey = location.pathname + location.search;
        if (typeof p.watchKey === 'string' && p.watchKey.indexOf('/watch/') !== -1) {
            var msgRoute = parseWatchRoute(p.watchKey);
            if (!msgRoute || msgRoute.titleId !== currentWatch.titleId ||
                (msgRoute.episodeId && currentWatch.episodeId && msgRoute.episodeId !== currentWatch.episodeId)) {
                return;
            }
        } else if (typeof window.__fsSyncWatchKey === 'function') {
            window.__fsSyncWatchKey();
        }

        var page = getInertiaPage();
        var props = (page && page.props) || {};
        var t = (props.title && Number(props.title.id) === currentWatch.titleId) ? props.title : null;
        var ep = props.episode || null;
        var cached = metaCache[String(currentWatch.titleId)] || {};

        var locale = (typeof props.locale === 'string' && props.locale.trim())
            ? props.locale.trim()
            : (cached.locale || ((location.pathname.match(/^\/([a-z]{2})\//i) || [])[1] || 'it').toLowerCase());

        var slug = (t && typeof t.slug === 'string' && t.slug.trim()) ? t.slug.trim() : (cached.slug || '');
        var name = (t && typeof t.name === 'string' && t.name.trim()) ? t.name.trim() : (cached.name || '');
        if (!name) return;

        var mediaType = (t && (t.type === 'tv' || t.type === 'movie'))
            ? t.type
            : ((ep || currentWatch.episodeId) ? 'tv' : (cached.mediaType || 'movie'));

        var episodeId = (ep && Number(ep.id) > 0) ? Number(ep.id) : currentWatch.episodeId;
        var seasonNumber = (ep && ep.season && Number(ep.season.number) > 0) ? Number(ep.season.number) : null;
        var episodeNumber = (ep && Number(ep.number) > 0) ? Number(ep.number) : null;
        var episodeName = (ep && typeof ep.name === 'string' && ep.name.trim()) ? ep.name.trim() : null;

        var titlePath = slug
            ? ('/' + locale + '/titles/' + currentWatch.titleId + '-' + slug +
                (mediaType === 'tv' && seasonNumber ? ('/season-' + seasonNumber) : ''))
            : (cached.titlePath || '');
        if (!titlePath) return;

        var coverFilename = extractImageFilename(t && t.images, ['cover', 'background', 'cover_mobile']) ||
            cached.coverFilename || '';
        var posterFilename = extractImageFilename(t && t.images, ['poster', 'cover_mobile', 'cover']) ||
            cached.posterFilename || '';
        var subIta = t && typeof t.sub_ita !== 'undefined' ? Boolean(t.sub_ita) : Boolean(cached.subIta);

        sendProgress({
            titleId: currentWatch.titleId,
            slug: slug,
            name: name,
            mediaType: mediaType,
            locale: locale,
            titlePath: titlePath,
            watchPath: currentKey,
            coverFilename: coverFilename,
            posterFilename: posterFilename,
            subIta: subIta,
            episodeId: mediaType === 'tv' ? episodeId : null,
            seasonNumber: mediaType === 'tv' ? seasonNumber : null,
            episodeNumber: mediaType === 'tv' ? episodeNumber : null,
            episodeName: mediaType === 'tv' ? episodeName : null,
            positionSec: Math.max(0, Math.floor(Number(p.pos) || 0)),
            durationSec: Math.max(0, Math.floor(Number(p.dur) || 0)),
            ended: Boolean(p.ended)
        });
    }

    function detectListingKind() {
        var page = getInertiaPage();
        var comp = page && typeof page.component === 'string' ? page.component : '';
        if (comp === 'Home/Home') return 'home';
        if (comp === 'Home/Movies') return 'movies';
        if (comp === 'Home/Shows') return 'shows';
        if (comp) return null;

        var p = location.pathname.replace(/\/+$/, '') || '/';
        if (p === '/' || /^\/[a-z]{2}$/i.test(p)) return 'home';
        if (/\/movies$/i.test(p)) return 'movies';
        if (/\/tv-shows$/i.test(p)) return 'shows';
        return null;
    }

    function getFilteredEntries(kind) {
        if (!kind || !entries.length) return [];
        if (kind === 'home') return entries.slice();
        var wanted = kind === 'movies' ? 'movie' : 'tv';
        return entries.filter(function (e) { return e && e.type === wanted; });
    }

    function resolveCdnBase() {
        var page = getInertiaPage();
        var cdn = page && page.props && typeof page.props.cdn_url === 'string' ? page.props.cdn_url.trim() : '';
        if (cdn && /^https:\/\/[a-z0-9.-]+$/i.test(cdn.replace(/\/+$/, ''))) {
            return cdn.replace(/\/+$/, '');
        }
        return 'https://cdn.' + location.hostname.replace(/^www\./i, '');
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

    function buildSignature(kind, isMobile, cdnBase, items) {
        var parts = [kind, isMobile ? 'm' : 'd', cdnBase, location.origin];
        for (var i = 0; i < items.length; i++) {
            var it = items[i];
            parts.push([
                it.titleId, it.titlePath, it.name, it.type,
                it.coverFilename, it.posterFilename, it.subIta ? '1' : '0',
                it.seasonNumber || 0, it.episodeNumber || 0,
                it.positionSec, it.durationSec
            ].join('|'));
        }
        return parts.join(';');
    }

    function createPlaySvg() {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('width', '1.2em');
        svg.setAttribute('height', '1.2em');
        svg.setAttribute('aria-hidden', 'true');
        var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('fill', 'currentColor');
        path.setAttribute('d', 'M8 5.14v14l11-7-11-7z');
        svg.appendChild(path);
        return svg;
    }

    function createProgressBar(pct) {
        var bar = document.createElement('div');
        bar.className = 'fs-cw-progress';
        var fill = document.createElement('div');
        fill.className = 'fs-cw-progress-fill';
        fill.style.width = pct + '%';
        bar.appendChild(fill);
        return bar;
    }

    function buildCard(entry, isMobile, cdnBase, currentLocale) {
        var card = document.createElement('a');
        card.className = 'fs-cw-card';
        card.href = location.origin + entry.titlePath;

        var media = document.createElement('div');
        media.className = 'fs-cw-media';

        var filename = isMobile
            ? (entry.posterFilename || entry.coverFilename)
            : (entry.coverFilename || entry.posterFilename);

        if (filename) {
            var img = document.createElement('img');
            img.className = 'fs-cw-img';
            img.src = cdnBase + '/images/' + filename;
            img.alt = entry.name || '';
            img.loading = 'lazy';
            img.addEventListener('error', function () {
                img.style.display = 'none';
                var fb = document.createElement('div');
                fb.className = 'fs-cw-fallback';
                fb.textContent = entry.name || '';
                media.appendChild(fb);
            });
            media.appendChild(img);
        } else {
            var fallback = document.createElement('div');
            fallback.className = 'fs-cw-fallback';
            fallback.textContent = entry.name || '';
            media.appendChild(fallback);
        }

        if (entry.subIta && currentLocale === 'it') {
            var sub = document.createElement('div');
            sub.className = 'sub-ita';
            sub.textContent = ' SUB ';
            media.appendChild(sub);
        }

        var pct = entry.durationSec > 0
            ? Math.max(2, Math.min(100, Math.round((entry.positionSec / entry.durationSec) * 100)))
            : 0;

        var epPrefix = (entry.type === 'tv' && entry.seasonNumber && entry.episodeNumber)
            ? ('S' + entry.seasonNumber + ':E' + entry.episodeNumber)
            : '';
        var posLabel = formatTime(entry.positionSec);
        var durLabel = formatTime(entry.durationSec);

        if (isMobile) {
            var overlay = document.createElement('div');
            overlay.className = 'fs-cw-mobile-overlay';

            var playBtn = document.createElement('div');
            playBtn.className = 'fs-cw-play-btn';
            playBtn.appendChild(createPlaySvg());
            overlay.appendChild(playBtn);

            var mobileLabel = document.createElement('div');
            mobileLabel.className = 'fs-cw-mobile-label';
            mobileLabel.textContent = epPrefix ? (epPrefix + ' · ' + posLabel) : posLabel;
            overlay.appendChild(mobileLabel);

            overlay.appendChild(createProgressBar(pct));
            media.appendChild(overlay);
        } else {
            var badge = document.createElement('div');
            badge.className = 'fs-cw-badge';
            badge.textContent = epPrefix ? (epPrefix + ' · ' + posLabel) : posLabel;
            media.appendChild(badge);
        }

        card.appendChild(media);

        if (!isMobile) {
            card.appendChild(createProgressBar(pct));
        }

        var caption = document.createElement('div');
        caption.className = 'fs-cw-caption';

        var titleEl = document.createElement('div');
        titleEl.className = 'fs-cw-name';
        titleEl.textContent = entry.name || '';
        caption.appendChild(titleEl);

        if (!isMobile) {
            var metaEl = document.createElement('div');
            metaEl.className = 'fs-cw-meta';
            metaEl.textContent = epPrefix
                ? (epPrefix + ' · ' + posLabel + ' / ' + durLabel)
                : (posLabel + ' / ' + durLabel);
            caption.appendChild(metaEl);
        }

        card.appendChild(caption);
        return card;
    }

    function removeRowIfPresent() {
        var existing = document.getElementById('fs-continue-watching');
        if (existing && existing.parentNode) {
            existing.parentNode.removeChild(existing);
        }
        lastRenderedSig = null;
    }

    function renderContinueWatching() {
        if (!document.body) return;
        var kind = detectListingKind();
        var items = getFilteredEntries(kind);
        if (!kind || !items.length) {
            removeRowIfPresent();
            return;
        }

        var slidersEl = document.querySelector('.font-vw.container > .sliders') ||
            document.querySelector('.sliders');
        if (!slidersEl || !slidersEl.parentNode) {
            if (mountRetries < 12) {
                mountRetries++;
                scheduleRender(200);
            }
            return;
        }
        mountRetries = 0;

        var isMobile = window.innerWidth < 800;
        var cdnBase = resolveCdnBase();
        var page = getInertiaPage();
        var currentLocale = (page && page.props && typeof page.props.locale === 'string' && page.props.locale.trim())
            ? page.props.locale.trim()
            : 'it';
        var sig = buildSignature(kind, isMobile, cdnBase, items);

        var existing = document.getElementById('fs-continue-watching');
        if (existing && existing.nextElementSibling === slidersEl &&
            existing.getAttribute('data-cw-sig') === sig) {
            return;
        }

        var section = document.createElement('section');
        section.id = 'fs-continue-watching';
        section.className = 'fs-cw-row ' + (isMobile ? 'fs-cw-mobile' : 'fs-cw-desktop');
        section.setAttribute('data-cw-sig', sig);

        var header = document.createElement('div');
        header.className = 'fs-cw-header';
        var headerTitle = document.createElement('span');
        headerTitle.className = 'fs-cw-title';
        headerTitle.textContent = 'Riprendi da dove sei rimasto';
        header.appendChild(headerTitle);
        section.appendChild(header);

        var slider = document.createElement('div');
        slider.className = 'fs-cw-slider';
        var track = document.createElement('div');
        track.className = 'fs-cw-track';

        for (var i = 0; i < items.length; i++) {
            track.appendChild(buildCard(items[i], isMobile, cdnBase, currentLocale));
        }

        slider.appendChild(track);
        section.appendChild(slider);

        if (existing && existing.parentNode) {
            existing.parentNode.removeChild(existing);
        }
        slidersEl.parentNode.insertBefore(section, slidersEl);
        lastRenderedSig = sig;
    }

    function scheduleRender(delayMs) {
        clearTimeout(renderTimer);
        renderTimer = setTimeout(renderContinueWatching, delayMs || 0);
    }

    window.__fsSetContinueWatching = function (raw) {
        try {
            var parsed = (typeof raw === 'string') ? JSON.parse(raw) : raw;
            entries = Array.isArray(parsed) ? parsed : [];
        } catch (e) {
            entries = [];
        }
        lastRenderedSig = null;
        harvestInertiaPage();
        scheduleRender(0);
        if (parseWatchRoute() && typeof window.__fsSyncWatchKey === 'function') {
            window.__fsSyncWatchKey();
        }
    };

    window.addEventListener('message', function (e) {
        var d = e.data;
        if (!d || typeof d !== 'object') return;
        if (d.__fsAgentReady) {
            if (parseWatchRoute() && typeof window.__fsSyncWatchKey === 'function') {
                window.__fsSyncWatchKey();
            }
            return;
        }
        if (d.__fsProgress) {
            handleProgressFromPlayer(d.__fsProgress);
        }
    });

    document.addEventListener('click', function (e) {
        var target = e.target;
        var anchor = target && target.closest ? target.closest('a[href*="/titles/"]') : null;
        if (anchor && harvestCardElement(anchor)) {
            saveMetaCache();
        }
    }, true);

    window.addEventListener('fs-route-change', function () {
        mountRetries = 0;
        setTimeout(function () {
            harvestInertiaPage();
            harvestDomCards();
            renderContinueWatching();
        }, 50);
        scheduleRender(350);
    });

    window.addEventListener('popstate', function () {
        mountRetries = 0;
        scheduleRender(80);
    });

    window.addEventListener('resize', function () {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(renderContinueWatching, 150);
    }, { passive: true });

    function observeSliders() {
        if (!window.MutationObserver || !document.documentElement) return;
        new MutationObserver(function () {
            clearTimeout(mutTimer);
            mutTimer = setTimeout(function () {
                harvestDomCards();
                if (detectListingKind() && entries.length) {
                    var existing = document.getElementById('fs-continue-watching');
                    var slidersEl = document.querySelector('.font-vw.container > .sliders') ||
                        document.querySelector('.sliders');
                    if (slidersEl && (!existing || existing.nextElementSibling !== slidersEl)) {
                        renderContinueWatching();
                    }
                }
            }, 250);
        }).observe(document.documentElement, { childList: true, subtree: true });
    }

    function onDomReady() {
        harvestInertiaPage();
        harvestDomCards();
        observeSliders();
        renderContinueWatching();
        sendSyncRequest();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', onDomReady);
    } else {
        onDomReady();
    }
    window.addEventListener('load', function () {
        harvestInertiaPage();
        harvestDomCards();
        renderContinueWatching();
    });
    sendSyncRequest();
})();
