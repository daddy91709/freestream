(function() {
    'use strict';

    let currentFocus = null;

    // Raccoglie tutti gli elementi cliccabili visibili
    function getFocusableElements() {
        const selector = 'a, button, input, select, textarea, [tabindex]:not([tabindex="-1"]), [role="button"], .play-button, .slider-item, .card';
        const elements = Array.from(document.querySelectorAll(selector));
        return elements.filter(el => {
            const rect = el.getBoundingClientRect();
            const style = window.getComputedStyle(el);
            return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
        });
    }

    function setFocus(el) {
        if (!el) return;
        if (currentFocus && currentFocus !== el) {
            currentFocus.removeAttribute('data-tv-focused');
        }
        currentFocus = el;
        el.setAttribute('data-tv-focused', 'true');
        el.focus();

        // Scroll fluido al centro dello schermo della TV
        el.scrollIntoView({
            behavior: 'smooth',
            block: 'center',
            inline: 'center'
        });
    }

    function findClosestElement(direction) {
        if (!currentFocus) {
            const all = getFocusableElements();
            return all.length > 0 ? all[0] : null;
        }

        const currentRect = currentFocus.getBoundingClientRect();
        const curCenterX = currentRect.left + currentRect.width / 2;
        const curCenterY = currentRect.top + currentRect.height / 2;

        const candidates = getFocusableElements().filter(el => el !== currentFocus);
        let bestCandidate = null;
        let minDistance = Infinity;

        for (const candidate of candidates) {
            const r = candidate.getBoundingClientRect();
            const cX = r.left + r.width / 2;
            const cY = r.top + r.height / 2;

            const dx = cX - curCenterX;
            const dy = cY - curCenterY;

            let isValid = false;
            if (direction === 'ArrowUp' && dy < -5) isValid = true;
            if (direction === 'ArrowDown' && dy > 5) isValid = true;
            if (direction === 'ArrowLeft' && dx < -5) isValid = true;
            if (direction === 'ArrowRight' && dx > 5) isValid = true;

            if (isValid) {
                // Calcola distanza euclidea pesata (favorisce l'asse principale)
                let distance;
                if (direction === 'ArrowUp' || direction === 'ArrowDown') {
                    distance = Math.abs(dy) * 1.0 + Math.abs(dx) * 2.5;
                } else {
                    distance = Math.abs(dx) * 1.0 + Math.abs(dy) * 2.5;
                }

                if (distance < minDistance) {
                    minDistance = distance;
                    bestCandidate = candidate;
                }
            }
        }

        return bestCandidate;
    }

    window.addEventListener('keydown', function(e) {
        // Se c'è un video a schermo intero attivo
        const video = document.querySelector('video');
        const isVideoActive = video && (document.fullscreenElement || window.innerHeight === screen.height);

        if (isVideoActive) {
            if (e.key === 'ArrowRight') {
                video.currentTime = Math.min(video.duration, video.currentTime + 10);
                e.preventDefault();
                return;
            } else if (e.key === 'ArrowLeft') {
                video.currentTime = Math.max(0, video.currentTime - 10);
                e.preventDefault();
                return;
            } else if (e.key === ' ' || e.key === 'Enter') {
                video.paused ? video.play() : video.pause();
                e.preventDefault();
                return;
            }
        }

        // Navigazione Spaziale D-Pad
        if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) {
            const nextEl = findClosestElement(e.key);
            if (nextEl) {
                setFocus(nextEl);
                e.preventDefault();
            }
        } else if (e.key === 'Enter') {
            if (currentFocus) {
                currentFocus.click();
            }
        }
    });

    // Inizializza il focus al caricamento
    window.addEventListener('DOMContentLoaded', () => {
        setTimeout(() => {
            const initial = getFocusableElements();
            if (initial.length > 0) {
                setFocus(initial[0]);
            }
        }, 800);
    });

    console.log("[TV Navigation] Spatial Navigation attiva.");
})();
