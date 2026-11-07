(function() {
    'use strict';

    let activeElement = null;

    // Raccoglie solo elementi cliccabili autentici e visibili
    function getFocusableElements() {
        const selector = 'a[href], button, input:not([type="hidden"]), select, textarea, [role="button"], [tabindex]:not([tabindex="-1"]), .cursor-pointer, .card, [onclick]';
        const raw = Array.from(document.querySelectorAll(selector));
        const filtered = [];

        for (let i = 0; i < raw.length; i++) {
            const el = raw[i];
            if (!el.isConnected) continue;
            if (el.offsetParent === null && el.tagName !== 'BODY') continue;

            const r = el.getBoundingClientRect();
            if (r.width < 14 || r.height < 14) continue;

            const style = window.getComputedStyle(el);
            if (style.display === 'none' || 
                style.visibility === 'hidden' || 
                style.opacity === '0' || 
                style.pointerEvents === 'none') {
                continue;
            }

            // Deduplicazione annidamento:
            // Se un elemento è figlio di uno già inserito, evita di aggiungerlo
            let isChild = false;
            for (let j = 0; j < filtered.length; j++) {
                if (filtered[j].contains(el)) {
                    isChild = true;
                    break;
                }
            }
            if (isChild) continue;

            // Se l'elemento corrente contiene elementi già aggiunti, sostituiscili
            for (let j = filtered.length - 1; j >= 0; j--) {
                if (el.contains(filtered[j])) {
                    filtered.splice(j, 1);
                }
            }

            filtered.push(el);
        }

        return filtered;
    }

    function setActiveFocus(el) {
        if (!el) return;

        if (activeElement && activeElement !== el) {
            activeElement.classList.remove('tv-active-focus');
        }

        activeElement = el;
        el.classList.add('tv-active-focus');

        // Scroll morbido sia verticale che orizzontale
        try {
            el.scrollIntoView({
                behavior: 'smooth',
                block: 'center',
                inline: 'center'
            });
        } catch (e) {
            // Fallback per browser datati
            el.scrollIntoView(false);
        }

        // Se l'elemento è dentro un contenitore a scorrimento orizzontale (es. slider film)
        let parent = el.parentElement;
        while (parent && parent !== document.body) {
            if (parent.scrollWidth > parent.clientWidth + 10) {
                const pRect = parent.getBoundingClientRect();
                const eRect = el.getBoundingClientRect();
                const delta = (eRect.left + eRect.width / 2) - (pRect.left + pRect.width / 2);
                if (Math.abs(delta) > 50) {
                    parent.scrollBy({ left: delta, behavior: 'smooth' });
                }
                break;
            }
            parent = parent.parentElement;
        }
    }

    function findNextElement(direction) {
        const elements = getFocusableElements();
        if (elements.length === 0) return null;

        if (!activeElement || !document.body.contains(activeElement)) {
            // Seleziona il primo elemento visibile in alto a sinistra
            const sorted = elements.slice().sort((a, b) => {
                const ra = a.getBoundingClientRect();
                const rb = b.getBoundingClientRect();
                return (ra.top * 2 + ra.left) - (rb.top * 2 + rb.left);
            });
            return sorted[0];
        }

        const cur = activeElement.getBoundingClientRect();
        const curCenterX = cur.left + cur.width / 2;
        const curCenterY = cur.top + cur.height / 2;

        let bestCandidate = null;
        let minScore = Infinity;

        for (let i = 0; i < elements.length; i++) {
            const el = elements[i];
            if (el === activeElement) continue;

            const r = el.getBoundingClientRect();
            const cX = r.left + r.width / 2;
            const cY = r.top + r.height / 2;

            let score = Infinity;

            if (direction === 'ArrowUp') {
                // L'elemento deve trovarsi sopra
                if (r.bottom <= cur.top + 15 || cY < curCenterY - 15) {
                    const distY = Math.max(0, cur.top - r.bottom);
                    let distX;
                    if (r.left <= cur.right && r.right >= cur.left) {
                        distX = 0; // Sovrapposizione orizzontale
                    } else if (r.right < cur.left) {
                        distX = cur.left - r.right;
                    } else {
                        distX = r.left - cur.right;
                    }
                    // Peso forte su Y per non saltare righe
                    score = (distY * 12.0) + (distX * 1.0);
                }
            } else if (direction === 'ArrowDown') {
                // L'elemento deve trovarsi sotto
                if (r.top >= cur.bottom - 15 || cY > curCenterY + 15) {
                    const distY = Math.max(0, r.top - cur.bottom);
                    let distX;
                    if (r.left <= cur.right && r.right >= cur.left) {
                        distX = 0;
                    } else if (r.right < cur.left) {
                        distX = cur.left - r.right;
                    } else {
                        distX = r.left - cur.right;
                    }
                    score = (distY * 12.0) + (distX * 1.0);
                }
            } else if (direction === 'ArrowLeft') {
                // L'elemento deve trovarsi a sinistra
                if (r.right <= cur.left + 15 || cX < curCenterX - 15) {
                    const distX = Math.max(0, cur.left - r.right);
                    let distY;
                    if (r.top <= cur.bottom && r.bottom >= cur.top) {
                        distY = 0; // Stessa riga / sovrapposizione verticale
                    } else if (r.bottom < cur.top) {
                        distY = cur.top - r.bottom;
                    } else {
                        distY = r.top - cur.bottom;
                    }
                    // Peso forte su Y per restare nella stessa riga
                    score = (distX * 1.0) + (distY * 18.0);
                }
            } else if (direction === 'ArrowRight') {
                // L'elemento deve trovarsi a destra
                if (r.left >= cur.right - 15 || cX > curCenterX + 15) {
                    const distX = Math.max(0, r.left - cur.right);
                    let distY;
                    if (r.top <= cur.bottom && r.bottom >= cur.top) {
                        distY = 0;
                    } else if (r.bottom < cur.top) {
                        distY = cur.top - r.bottom;
                    } else {
                        distY = r.top - cur.bottom;
                    }
                    score = (distX * 1.0) + (distY * 18.0);
                }
            }

            if (score < minScore) {
                minScore = score;
                bestCandidate = el;
            }
        }

        return bestCandidate;
    }

    // Navigazione invocata da Android Activity
    window.tvNavigate = function(direction) {
        // Gestione speciale se player video attivo a schermo intero
        const video = document.querySelector('video');
        const isFullscreen = video && (document.fullscreenElement || window.innerHeight === screen.height);

        if (isFullscreen) {
            if (direction === 'ArrowRight') {
                video.currentTime = Math.min(video.duration || 99999, (video.currentTime || 0) + 10);
                return;
            } else if (direction === 'ArrowLeft') {
                video.currentTime = Math.max(0, (video.currentTime || 0) - 10);
                return;
            } else if (direction === 'Enter') {
                video.paused ? video.play() : video.pause();
                return;
            }
        }

        if (direction === 'Enter') {
            window.tvClick();
            return;
        }

        const next = findNextElement(direction);
        if (next) {
            setActiveFocus(next);
        } else {
            // Scroll di fallback se non ci sono elementi visibili nella direzione richiesta
            if (direction === 'ArrowUp') {
                window.scrollBy({ top: -350, behavior: 'smooth' });
                setTimeout(() => {
                    const fallback = findNextElement('ArrowUp');
                    if (fallback) setActiveFocus(fallback);
                }, 200);
            } else if (direction === 'ArrowDown') {
                window.scrollBy({ top: 350, behavior: 'smooth' });
                setTimeout(() => {
                    const fallback = findNextElement('ArrowDown');
                    if (fallback) setActiveFocus(fallback);
                }, 200);
            } else if (direction === 'ArrowLeft') {
                window.scrollBy({ left: -300, behavior: 'smooth' });
            } else if (direction === 'ArrowRight') {
                window.scrollBy({ left: 300, behavior: 'smooth' });
            }
        }
    };

    window.tvClick = function() {
        if (!activeElement) {
            const items = getFocusableElements();
            if (items.length > 0) setActiveFocus(items[0]);
            return;
        }

        // Se è un campo di testo o input
        if (activeElement.tagName === 'INPUT' || activeElement.tagName === 'TEXTAREA') {
            activeElement.focus();
            return;
        }

        // Simula click reale
        try {
            activeElement.click();
            const mouseEvent = new MouseEvent('click', {
                bubbles: true,
                cancelable: true,
                view: window
            });
            activeElement.dispatchEvent(mouseEvent);
        } catch (e) {
            console.error("[FreeStream Nav] Errore simulazione click:", e);
        }
    };

    window.tvClearFocus = function() {
        if (activeElement) {
            activeElement.classList.remove('tv-active-focus');
            activeElement = null;
        }
    };

    window.tvFocusNear = function(x, y) {
        const elements = getFocusableElements();
        let closest = null;
        let minD = Infinity;
        for (let i = 0; i < elements.length; i++) {
            const r = elements[i].getBoundingClientRect();
            const cX = r.left + r.width / 2;
            const cY = r.top + r.height / 2;
            const d = Math.hypot(cX - x, cY - y);
            if (d < minD) {
                minD = d;
                closest = elements[i];
            }
        }
        if (closest) {
            setActiveFocus(closest);
        }
    };

    // Inizializza focus al termine del caricamento della pagina
    function initFocus() {
        setTimeout(() => {
            const items = getFocusableElements();
            if (items.length > 0 && !activeElement) {
                setActiveFocus(items[0]);
            }
        }, 1000);
    }

    if (document.readyState === 'complete') {
        initFocus();
    } else {
        window.addEventListener('load', initFocus);
    }

    console.log("[FreeStream TV Navigation Engine 2.1] Inizializzato con successo.");
})();
