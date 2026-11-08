(function() {
    'use strict';

    if (window.__fsAdblock) return;
    window.__fsAdblock = true;

    // Blocca window.open per evitare tab/popunder pubblicitari
    window.open = function(url) {
        if (!url || typeof url !== 'string') return null;

        // Consenti solo navigazioni interne sul dominio corrente
        const currentHost = window.location.hostname;
        try {
            const parsed = new URL(url, window.location.href);
            if (parsed.hostname === currentHost || parsed.hostname.endsWith('.' + currentHost)) {
                window.location.href = url;
            } else {
                console.log("[AdBlock] Bloccato window.open su dominio esterno:", url);
            }
        } catch(e) {
            console.log("[AdBlock] URL non valido bloccato:", url);
        }
        return null;
    };

    // Rimuove attributi target="_blank" per non aprire nuove pagine
    function sanitizeLinks() {
        const links = document.querySelectorAll('a[target="_blank"]');
        for (let i = 0; i < links.length; i++) {
            links[i].removeAttribute('target');
        }
    }

    document.addEventListener('DOMContentLoaded', sanitizeLinks);
    setInterval(sanitizeLinks, 2000);
})();
