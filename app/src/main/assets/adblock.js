(function () {
    'use strict';

    if (window.__fsAdblock) return;
    window.__fsAdblock = true;

    // Blocca window.open per evitare tab/popunder pubblicitari
    window.open = function (url) {
        if (!url || typeof url !== 'string') return null;

        // Consenti solo navigazioni interne sul dominio corrente
        const currentHost = window.location.hostname;
        try {
            const parsed = new URL(url, window.location.href);
            if (parsed.hostname === currentHost || parsed.hostname.endsWith('.' + currentHost)) {
                window.location.href = url;
            } else {
                console.log('[AdBlock] Bloccato window.open su dominio esterno:', url);
            }
        } catch (e) {
            console.log('[AdBlock] URL non valido bloccato:', url);
        }
        return null;
    };

    function sanitizeIn(root) {
        if (!root) return;
        if (root.nodeType === 1 && root.getAttribute && root.getAttribute('target') === '_blank') {
            root.removeAttribute('target');
        }
        if (typeof root.querySelectorAll === 'function') {
            const links = root.querySelectorAll('a[target="_blank"]');
            for (let i = 0; i < links.length; i++) {
                links[i].removeAttribute('target');
            }
        }
    }

    function stripTargetOnEvent(e) {
        const t = e.target;
        if (!t || typeof t.closest !== 'function') return;
        const anchor = t.closest('a[target="_blank"]');
        if (anchor) anchor.removeAttribute('target');
    }

    // Rimozione al volo prima che il browser processi il click
    document.addEventListener('click', stripTargetOnEvent, true);
    document.addEventListener('auxclick', stripTargetOnEvent, true);

    function startSanitizing() {
        sanitizeIn(document);
        if (!window.MutationObserver || !document.documentElement) return;
        new MutationObserver(function (mutations) {
            for (let i = 0; i < mutations.length; i++) {
                const m = mutations[i];
                if (m.type === 'attributes') {
                    if (m.target.getAttribute && m.target.getAttribute('target') === '_blank') {
                        m.target.removeAttribute('target');
                    }
                } else if (m.type === 'childList') {
                    for (let j = 0; j < m.addedNodes.length; j++) {
                        sanitizeIn(m.addedNodes[j]);
                    }
                }
            }
        }).observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['target']
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', startSanitizing);
    } else {
        startSanitizing();
    }
})();
