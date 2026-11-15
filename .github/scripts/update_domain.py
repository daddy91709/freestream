#!/usr/bin/env python3
"""
Script eseguito da GitHub Actions per mantenere aggiornato `domains.json`
come unica fonte di verità del repository FreeStream.

Strategia di risoluzione (senza dipendenze da repository di terze parti):
1. Legge l'ultimo dominio noto da `domains.json` (ed eventuale `old_domain`)
   e ne segue la catena di redirect HTTP/HTTPS.
2. Se il dominio precedente non risponde, interroga i log pubblici di
   Certificate Transparency (crt.sh) per `streamingcommunityz.*` per scoprire
   automaticamente i nuovi TLD registrati.
3. Verifica che il dominio finale appartenga a `streamingcommunityz.<tld>` e
   presenti la firma autentica dell'applicazione (Laravel/Inertia/Ziggy).
"""

import json
import logging
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

import requests
import urllib3

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

REPO_ROOT = Path(__file__).resolve().parents[2]
DOMAINS_FILE = REPO_ROOT / "domains.json"
OFFICIAL_LABEL = "streamingcommunityz"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/125.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
}


def is_official_host(hostname: str | None) -> bool:
    if not hostname:
        return False
    labels = hostname.lower().split(".")
    return len(labels) >= 2 and labels[-2] == OFFICIAL_LABEL


def origin_of(url: str) -> str | None:
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        return None
    port = f":{parsed.port}" if parsed.port else ""
    return f"{parsed.scheme}://{parsed.hostname.lower()}{port}/"


def is_genuine_page(resp: requests.Response) -> bool:
    if not is_official_host(urlparse(resp.url).hostname):
        return False

    head = resp.text[:65536]
    if "const Ziggy" in head and '"titles.watch"' in head:
        return True
    if "streamingcommunity_session" in resp.cookies:
        return True
    if "X-Inertia" in resp.headers.get("Vary", "") or "X-Inertia" in resp.headers:
        return True
    return False


def probe_candidate(url: str, timeout: int = 8) -> str | None:
    current = url
    for _ in range(10):
        try:
            resp = requests.get(
                current,
                headers=HEADERS,
                timeout=timeout,
                allow_redirects=False,
                verify=False,
            )
            if resp.status_code in (301, 302, 303, 307, 308) and "Location" in resp.headers:
                nxt = resp.headers["Location"]
                if nxt.startswith("/"):
                    p = urlparse(current)
                    nxt = f"{p.scheme}://{p.netloc}{nxt}"
                logging.info("Redirect: %s -> %s (HTTP %s)", current, nxt, resp.status_code)
                current = nxt
                continue

            if resp.status_code == 200 and is_genuine_page(resp):
                final_origin = origin_of(resp.url)
                if final_origin:
                    logging.info("Dominio attivo e verificato: %s", final_origin)
                    return final_origin
            return None
        except requests.RequestException as exc:
            logging.warning("Connessione fallita per %s: %s", current, exc)
            return None
    return None


def load_candidates_from_json() -> list[str]:
    if not DOMAINS_FILE.exists():
        return []
    try:
        data = json.loads(DOMAINS_FILE.read_text(encoding="utf-8"))
        sc = data.get("streamingcommunity", {})
        candidates: list[str] = []
        full_url = sc.get("full_url", "").strip()
        if full_url:
            candidates.append(full_url)
        domain = sc.get("domain", "").strip()
        if domain:
            candidates.append(f"https://{OFFICIAL_LABEL}.{domain}/")
        old_domain = sc.get("old_domain", "").strip()
        if old_domain:
            candidates.append(f"https://{OFFICIAL_LABEL}.{old_domain}/")
        # Rimuovi duplicati preservando l'ordine
        return list(dict.fromkeys(candidates))
    except Exception as exc:
        logging.warning("Impossibile leggere %s: %s", DOMAINS_FILE, exc)
        return []


def discover_from_crt_sh() -> list[str]:
    """
    Fallback automatico tramite Certificate Transparency (crt.sh):
    trova i certificati SSL emessi più di recente per `streamingcommunityz.*`.
    """
    logging.info("Interrogazione Certificate Transparency (crt.sh) per %s.* ...", OFFICIAL_LABEL)
    url = f"https://crt.sh/?q={OFFICIAL_LABEL}.%25&output=json"
    try:
        resp = requests.get(url, headers=HEADERS, timeout=15)
        if resp.status_code != 200:
            return []
        entries = resp.json()
        # Ordina per ID certificato decrescente (i più recenti prima)
        entries.sort(key=lambda e: e.get("id", 0), reverse=True)
        hosts: list[str] = []
        for entry in entries:
            names = str(entry.get("name_value", "")).splitlines()
            for name in names:
                host = name.strip().lstrip("*.")
                if is_official_host(host):
                    root_host = ".".join(host.lower().split(".")[-2:])
                    candidate = f"https://{root_host}/"
                    if candidate not in hosts:
                        hosts.append(candidate)
        return hosts[:15]
    except Exception as exc:
        logging.warning("Errore durante la query a crt.sh: %s", exc)
        return []


def resolve_active_domain() -> str | None:
    tried: set[str] = set()

    for candidate in load_candidates_from_json():
        origin = origin_of(candidate)
        if not origin or origin in tried:
            continue
        tried.add(origin)
        logging.info("Verifica candidato da domains.json: %s", origin)
        winner = probe_candidate(origin)
        if winner:
            return winner

    for candidate in discover_from_crt_sh():
        origin = origin_of(candidate)
        if not origin or origin in tried:
            continue
        tried.add(origin)
        logging.info("Verifica candidato da Certificate Transparency: %s", origin)
        winner = probe_candidate(origin)
        if winner:
            return winner

    return None


def update_domains_file(active_origin: str) -> bool:
    parsed = urlparse(active_origin)
    host_parts = (parsed.hostname or "").split(".")
    new_tld = host_parts[-1] if len(host_parts) >= 2 else ""

    existing_data: dict = {}
    if DOMAINS_FILE.exists():
        try:
            existing_data = json.loads(DOMAINS_FILE.read_text(encoding="utf-8"))
        except Exception:
            existing_data = {}

    current_sc = existing_data.get("streamingcommunity", {})
    current_url = current_sc.get("full_url", "")
    current_tld = current_sc.get("domain", "")

    if current_url == active_origin and current_tld == new_tld:
        logging.info("Il dominio in domains.json è già aggiornato (%s). Nessuna modifica.", active_origin)
        return False

    old_tld = current_tld if current_tld and current_tld != new_tld else current_sc.get("old_domain", "")
    now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

    existing_data["streamingcommunity"] = {
        "domain": new_tld,
        "full_url": active_origin,
        "old_domain": old_tld,
        "time_change": now_str,
    }

    DOMAINS_FILE.write_text(json.dumps(existing_data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    logging.info("Aggiornato %s -> %s (old: %s)", DOMAINS_FILE, active_origin, old_tld)
    return True


def main() -> int:
    active = resolve_active_domain()
    if not active:
        logging.error("Nessun dominio attivo e verificato trovato.")
        return 1

    update_domains_file(active)
    return 0


if __name__ == "__main__":
    sys.exit(main())
