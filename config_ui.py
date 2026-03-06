#!/usr/bin/env python3
"""Simple config UI and API for managing per-repo updater settings."""

import json
import logging
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

from ruamel.yaml import YAML


logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
)
logger = logging.getLogger(__name__)

yaml = YAML()
yaml.preserve_quotes = True

CONFIG_PATH = Path('/app/config.yml')
CACHE_PATH = Path('/srv/fdroid/tmp/cache.json')
HOST = '127.0.0.1'
PORT = 5001


def load_config():
    if not CONFIG_PATH.exists():
        return {}
    with open(CONFIG_PATH, 'r') as f:
        return yaml.load(f) or {}


def save_config(config):
    with open(CONFIG_PATH, 'w') as f:
        yaml.dump(config, f)


def load_cache():
    if not CACHE_PATH.exists():
        return {}
    try:
        with open(CACHE_PATH, 'r') as f:
            return json.load(f) or {}
    except Exception:
        return {}


def save_cache(cache):
    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(CACHE_PATH, 'w') as f:
        json.dump(cache, f, indent=2)


def _to_bool(value, default=False):
    if isinstance(value, bool):
        return value
    if value is None:
        return default
    if isinstance(value, str):
        return value.strip().lower() in ('1', 'true', 'yes', 'on')
    return bool(value)


def _repo_name_from_item(item):
    return item if isinstance(item, str) else item.get('repo')


def list_repos(config, cache):
    defaults = config.get('defaults', {}) or {}
    repos = []

    for i, item in enumerate(config.get('github_repos', []) or []):
        if isinstance(item, str):
            repo_name = item
            include_prereleases = defaults.get('include_prereleases', True)
            include_drafts = defaults.get('include_drafts', False)
            max_releases = defaults.get('max_releases', 5)
            enabled = defaults.get('enabled', True)
            apk_pattern = defaults.get('apk_pattern', config.get('apk_pattern', r'.*\\.apk$'))
        else:
            repo_name = item.get('repo')
            include_prereleases = item.get('include_prereleases', defaults.get('include_prereleases', True))
            include_drafts = item.get('include_drafts', defaults.get('include_drafts', False))
            max_releases = item.get('max_releases', defaults.get('max_releases', 5))
            enabled = item.get('enabled', defaults.get('enabled', True))
            apk_pattern = item.get('apk_pattern', defaults.get('apk_pattern', config.get('apk_pattern', r'.*\\.apk$')))

        if not repo_name or not enabled:
            continue

        repo_cache = cache.get(repo_name, {}) if isinstance(cache, dict) else {}
        processed = repo_cache.get('processed_releases', {}) if isinstance(repo_cache, dict) else {}
        checked_entries = []
        for release_id, entry in processed.items():
            if not isinstance(entry, dict):
                continue
            checked_entries.append({
                'release_id': str(release_id),
                'tag': entry.get('tag', ''),
                'type': entry.get('type', ''),
                'processed_at': entry.get('processed_at', ''),
                'apks_found': entry.get('apks_found', 0),
                'apks_downloaded': entry.get('apks_downloaded', 0),
            })
        checked_entries.sort(key=lambda x: x.get('processed_at', ''), reverse=True)

        repos.append({
            'id': i,
            'repo': repo_name,
            'include_prereleases': bool(include_prereleases),
            'include_drafts': bool(include_drafts),
            'max_releases': int(max_releases) if str(max_releases).isdigit() else 5,
            'enabled': bool(enabled),
            'apk_pattern': str(apk_pattern),
            'checked_entries': checked_entries,
        })

    return repos


def update_repo(config, repo_id, payload):
    github_repos = config.get('github_repos', []) or []
    if repo_id < 0 or repo_id >= len(github_repos):
        return False, 'Repo not found'

    current = github_repos[repo_id]
    current_repo_name = _repo_name_from_item(current)

    updated = {
        'repo': payload.get('repo', current_repo_name),
        'include_prereleases': _to_bool(payload.get('include_prereleases'), True),
        'include_drafts': _to_bool(payload.get('include_drafts'), False),
        'max_releases': int(payload.get('max_releases', 5)),
        'enabled': _to_bool(payload.get('enabled'), True),
        'apk_pattern': payload.get('apk_pattern', r'.*\\.apk$') or r'.*\\.apk$',
    }

    if not updated['repo']:
        return False, 'Repo is required'

    github_repos[repo_id] = updated
    config['github_repos'] = github_repos
    save_config(config)
    return True, updated


def add_repo(config, payload):
    repo = (payload.get('repo') or '').strip()
    if not repo:
        return False, 'Repo is required'

    github_repos = config.get('github_repos', [])
    if not isinstance(github_repos, list):
        github_repos = []

    new_entry = {
        'repo': repo,
        'include_prereleases': _to_bool(payload.get('include_prereleases'), True),
        'include_drafts': _to_bool(payload.get('include_drafts'), False),
        'max_releases': int(payload.get('max_releases', 5)),
        'enabled': _to_bool(payload.get('enabled'), True),
        'apk_pattern': payload.get('apk_pattern', r'.*\\.apk$') or r'.*\\.apk$',
    }

    github_repos.append(new_entry)
    config['github_repos'] = github_repos
    save_config(config)
    return True, new_entry


def delete_checked_entry(config, cache, repo_id, release_id):
    github_repos = config.get('github_repos', []) or []
    if repo_id < 0 or repo_id >= len(github_repos):
        return False, 'Repo not found'

    repo_name = _repo_name_from_item(github_repos[repo_id])
    if not repo_name:
        return False, 'Repo not found'

    repo_cache = cache.get(repo_name)
    if not isinstance(repo_cache, dict):
        return False, 'Checked entry not found'

    processed = repo_cache.get('processed_releases')
    if not isinstance(processed, dict) or release_id not in processed:
        return False, 'Checked entry not found'

    del processed[release_id]
    save_cache(cache)
    return True, {'repo': repo_name, 'release_id': release_id}


HTML = """<!doctype html>
<html lang=\"en\">
<head>
  <meta charset=\"utf-8\" />
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
  <title>Repo Config</title>
  <style>
    :root {
      --bg: #f6f8fb;
      --panel: #ffffff;
      --text: #1c2430;
      --muted: #6b7380;
      --line: #dde3ea;
      --brand: #0f766e;
      --brand-hover: #115e59;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: \"Segoe UI\", \"Helvetica Neue\", Arial, sans-serif;
      background: radial-gradient(circle at top left, #ecfeff 0%, var(--bg) 45%);
      color: var(--text);
    }
    .wrap { max-width: 1100px; margin: 32px auto; padding: 0 16px; }
    h1 { margin: 0 0 8px; font-size: 28px; }
    .sub { margin: 0 0 20px; color: var(--muted); }
    .card {
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 12px;
      overflow: hidden;
    }
    table { width: 100%; border-collapse: collapse; }
    th, td {
      text-align: left;
      padding: 12px 14px;
      border-bottom: 1px solid var(--line);
      font-size: 14px;
      vertical-align: top;
    }
    th { background: #f9fbfd; color: #4b5563; font-weight: 600; }
    tr:last-child td { border-bottom: none; }
    .btn {
      border: 1px solid var(--brand);
      background: var(--brand);
      color: #fff;
      border-radius: 8px;
      font-size: 13px;
      padding: 8px 12px;
      cursor: pointer;
    }
    .btn:hover { background: var(--brand-hover); }
    .btn.small { padding: 5px 8px; font-size: 12px; }
    .btn.ghost { background: #fff; color: #334155; border-color: #cbd5e1; }
    .pill {
      display: inline-block;
      padding: 4px 8px;
      border-radius: 999px;
      border: 1px solid #cfd6dd;
      background: #f8fafc;
      font-size: 12px;
    }
    .entry-list { display: flex; flex-direction: column; gap: 8px; min-width: 300px; }
    .entry {
      border: 1px solid #dbe2e8;
      border-radius: 8px;
      padding: 8px;
      background: #fbfcfd;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .entry-main { font-size: 12px; color: #334155; }
    .entry-meta { color: #64748b; }
    .overlay {
      position: fixed;
      inset: 0;
      background: rgba(15, 23, 42, 0.35);
      display: none;
      align-items: center;
      justify-content: center;
      padding: 16px;
    }
    .overlay.open { display: flex; }
    .modal {
      width: 100%;
      max-width: 560px;
      background: #fff;
      border-radius: 12px;
      border: 1px solid var(--line);
      padding: 16px;
    }
    .modal h2 { margin: 0 0 14px; font-size: 20px; }
    .field { margin-bottom: 10px; }
    label { display: block; font-size: 13px; margin-bottom: 5px; color: #374151; }
    input[type=\"text\"], input[type=\"number\"] {
      width: 100%;
      border: 1px solid #cfd6dd;
      border-radius: 8px;
      padding: 9px 10px;
      font-size: 14px;
    }
    .row { display: flex; gap: 12px; }
    .row .field { flex: 1; }
    .check { display: flex; align-items: center; gap: 8px; font-size: 14px; }
    .actions { margin-top: 14px; display: flex; gap: 8px; justify-content: flex-end; }
    .msg { margin-top: 10px; font-size: 13px; color: #dc2626; }
    @media (max-width: 900px) {
      table, thead, tbody, tr, th, td { display: block; }
      thead { display: none; }
      tr { border-bottom: 1px solid var(--line); }
      td { border: none; padding: 8px 14px; }
      .entry-list { min-width: 0; }
    }
  </style>
</head>
<body>
  <div class=\"wrap\">
    <h1>Checked Repositories</h1>
    <p class=\"sub\">Edit per-repo settings, view checked updates, and delete entries to mark them as not checked.</p>
    <div style=\"margin: 0 0 12px;\">
      <button id=\"newRepoBtn\" class=\"btn\">New Repo</button>
    </div>
    <div class=\"card\">
      <table>
        <thead>
          <tr>
            <th>Repository</th>
            <th>Pre-releases</th>
            <th>Drafts</th>
            <th>Max releases</th>
            <th>Checked updates</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody id=\"repoRows\"></tbody>
      </table>
    </div>
  </div>

  <div id=\"overlay\" class=\"overlay\">
    <div class=\"modal\">
      <h2>Edit Repository</h2>
      <form id=\"repoForm\">
        <input type=\"hidden\" id=\"repoId\" />
        <div class=\"field\">
          <label for=\"repo\">Repository (owner/name)</label>
          <input id=\"repo\" type=\"text\" required />
        </div>
        <div class=\"row\">
          <div class=\"field\">
            <label for=\"max_releases\">Max releases</label>
            <input id=\"max_releases\" type=\"number\" min=\"0\" required />
          </div>
          <div class=\"field\">
            <label for=\"apk_pattern\">APK regex</label>
            <input id=\"apk_pattern\" type=\"text\" required />
          </div>
        </div>
        <label class=\"check\"><input id=\"include_prereleases\" type=\"checkbox\" /> Include pre-releases</label>
        <label class=\"check\"><input id=\"include_drafts\" type=\"checkbox\" /> Include drafts</label>
        <label class=\"check\"><input id=\"enabled\" type=\"checkbox\" /> Enabled</label>
        <div class=\"actions\">
          <button type=\"button\" class=\"btn ghost\" id=\"cancelBtn\">Cancel</button>
          <button type=\"submit\" class=\"btn\">Save</button>
        </div>
        <div id=\"msg\" class=\"msg\"></div>
      </form>
    </div>
  </div>

  <script>
    const rows = document.getElementById('repoRows');
    const overlay = document.getElementById('overlay');
    const form = document.getElementById('repoForm');
    const msg = document.getElementById('msg');
    let formMode = 'edit';

    function escapeHtml(value) {
      return String(value)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
    }

    async function loadRepos() {
      const res = await fetch('/api/repos');
      const data = await res.json();
      rows.innerHTML = '';

      if (!data.repos.length) {
        rows.innerHTML = '<tr><td colspan="6">No enabled repositories found.</td></tr>';
        return;
      }

      for (const repo of data.repos) {
        const entriesHtml = repo.checked_entries.length
          ? `<div class="entry-list">${repo.checked_entries.map((entry) => `
              <div class="entry">
                <div class="entry-main">
                  <strong>${escapeHtml(entry.tag || entry.release_id)}</strong>
                  <span class="entry-meta"> (${escapeHtml(entry.type || 'stable')}) • APKs ${entry.apks_downloaded}/${entry.apks_found}</span>
                </div>
                <button class="btn ghost small" data-delete-id="${repo.id}" data-release-id="${encodeURIComponent(entry.release_id)}">Delete</button>
              </div>
            `).join('')}</div>`
          : '<span class="pill">None</span>';

        const tr = document.createElement('tr');
        tr.innerHTML = `
          <td><strong>${escapeHtml(repo.repo)}</strong></td>
          <td><span class="pill">${repo.include_prereleases ? 'Yes' : 'No'}</span></td>
          <td><span class="pill">${repo.include_drafts ? 'Yes' : 'No'}</span></td>
          <td>${repo.max_releases}</td>
          <td>${entriesHtml}</td>
          <td><button class="btn" data-id="${repo.id}">Edit</button></td>
        `;
        tr.querySelector('button[data-id]').addEventListener('click', () => openEditor(repo));
        tr.querySelectorAll('[data-delete-id]').forEach((btn) => {
          btn.addEventListener('click', async () => {
            const repoId = btn.getAttribute('data-delete-id');
            const releaseId = btn.getAttribute('data-release-id');
            const delRes = await fetch(`/api/repos/${repoId}/checked/${releaseId}`, { method: 'DELETE' });
            if (delRes.ok) {
              await loadRepos();
            }
          });
        });
        rows.appendChild(tr);
      }
    }

    function openEditor(repo) {
      formMode = 'edit';
      msg.textContent = '';
      document.querySelector('.modal h2').textContent = 'Edit Repository';
      document.getElementById('repoId').value = repo.id;
      document.getElementById('repo').value = repo.repo;
      document.getElementById('max_releases').value = repo.max_releases;
      document.getElementById('apk_pattern').value = repo.apk_pattern;
      document.getElementById('include_prereleases').checked = repo.include_prereleases;
      document.getElementById('include_drafts').checked = repo.include_drafts;
      document.getElementById('enabled').checked = repo.enabled;
      overlay.classList.add('open');
    }

    function openNewRepo() {
      formMode = 'new';
      msg.textContent = '';
      document.querySelector('.modal h2').textContent = 'Add Repository';
      document.getElementById('repoId').value = '';
      document.getElementById('repo').value = '';
      document.getElementById('max_releases').value = 5;
      document.getElementById('apk_pattern').value = '.*\\\\.apk$';
      document.getElementById('include_prereleases').checked = true;
      document.getElementById('include_drafts').checked = false;
      document.getElementById('enabled').checked = true;
      overlay.classList.add('open');
    }

    function closeEditor() {
      overlay.classList.remove('open');
    }

    document.getElementById('newRepoBtn').addEventListener('click', openNewRepo);
    document.getElementById('cancelBtn').addEventListener('click', closeEditor);
    overlay.addEventListener('click', (e) => {
      if (e.target === overlay) closeEditor();
    });

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      msg.textContent = '';
      const repoId = document.getElementById('repoId').value;
      const payload = {
        repo: document.getElementById('repo').value.trim(),
        max_releases: Number(document.getElementById('max_releases').value),
        apk_pattern: document.getElementById('apk_pattern').value,
        include_prereleases: document.getElementById('include_prereleases').checked,
        include_drafts: document.getElementById('include_drafts').checked,
        enabled: document.getElementById('enabled').checked,
      };

      let res;
      if (formMode === 'new') {
        res = await fetch('/api/repos', {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify(payload),
        });
      } else {
        res = await fetch(`/api/repos/${repoId}`, {
          method: 'PUT',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify(payload),
        });
      }

      const result = await res.json();
      if (!res.ok) {
        msg.textContent = result.error || 'Failed to save';
        return;
      }

      closeEditor();
      await loadRepos();
    });

    loadRepos();
  </script>
</body>
</html>
"""


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, payload, status=HTTPStatus.OK):
        data = json.dumps(payload).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_html(self, content):
        data = content.encode('utf-8')
        self.send_response(HTTPStatus.OK)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ('/admin', '/admin/'):
            self._send_html(HTML)
            return
        if path == '/api/repos':
            try:
                config = load_config()
                cache = load_cache()
                self._send_json({'repos': list_repos(config, cache)})
            except Exception as exc:
                logger.exception('Failed to load repositories')
                self._send_json({'error': str(exc)}, status=HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        self.send_error(HTTPStatus.NOT_FOUND)

    def do_PUT(self):
        path = urlparse(self.path).path
        if not path.startswith('/api/repos/'):
            self.send_error(HTTPStatus.NOT_FOUND)
            return

        repo_id_part = path.replace('/api/repos/', '', 1)
        if not repo_id_part.isdigit():
            self._send_json({'error': 'Invalid repo id'}, status=HTTPStatus.BAD_REQUEST)
            return
        repo_id = int(repo_id_part)

        content_length = int(self.headers.get('Content-Length', 0))
        raw = self.rfile.read(content_length) if content_length > 0 else b'{}'

        try:
            payload = json.loads(raw.decode('utf-8'))
            config = load_config()
            ok, result = update_repo(config, repo_id, payload)
            if not ok:
                self._send_json({'error': result}, status=HTTPStatus.BAD_REQUEST)
                return
            self._send_json({'ok': True, 'repo': result})
        except ValueError:
            self._send_json({'error': 'Invalid numeric value'}, status=HTTPStatus.BAD_REQUEST)
        except Exception as exc:
            logger.exception('Failed to update repo config')
            self._send_json({'error': str(exc)}, status=HTTPStatus.INTERNAL_SERVER_ERROR)

    def do_POST(self):
        path = urlparse(self.path).path
        if path != '/api/repos':
            self.send_error(HTTPStatus.NOT_FOUND)
            return

        content_length = int(self.headers.get('Content-Length', 0))
        raw = self.rfile.read(content_length) if content_length > 0 else b'{}'

        try:
            payload = json.loads(raw.decode('utf-8'))
            config = load_config()
            ok, result = add_repo(config, payload)
            if not ok:
                self._send_json({'error': result}, status=HTTPStatus.BAD_REQUEST)
                return
            self._send_json({'ok': True, 'repo': result}, status=HTTPStatus.CREATED)
        except ValueError:
            self._send_json({'error': 'Invalid numeric value'}, status=HTTPStatus.BAD_REQUEST)
        except Exception as exc:
            logger.exception('Failed to add repo config')
            self._send_json({'error': str(exc)}, status=HTTPStatus.INTERNAL_SERVER_ERROR)

    def do_DELETE(self):
        path = urlparse(self.path).path
        parts = [p for p in path.split('/') if p]
        # Expected format: /api/repos/<repo_id>/checked/<release_id>
        if len(parts) != 5 or parts[0] != 'api' or parts[1] != 'repos' or parts[3] != 'checked':
            self.send_error(HTTPStatus.NOT_FOUND)
            return

        repo_id_part = parts[2]
        release_id = unquote(parts[4])
        if not repo_id_part.isdigit():
            self._send_json({'error': 'Invalid repo id'}, status=HTTPStatus.BAD_REQUEST)
            return

        try:
            config = load_config()
            cache = load_cache()
            ok, result = delete_checked_entry(config, cache, int(repo_id_part), release_id)
            if not ok:
                self._send_json({'error': result}, status=HTTPStatus.BAD_REQUEST)
                return
            self._send_json({'ok': True, 'deleted': result})
        except Exception as exc:
            logger.exception('Failed to delete checked entry')
            self._send_json({'error': str(exc)}, status=HTTPStatus.INTERNAL_SERVER_ERROR)

    def log_message(self, fmt, *args):
        logger.info('%s - %s', self.address_string(), fmt % args)


if __name__ == '__main__':
    logger.info('Starting config UI server on http://%s:%d/admin', HOST, PORT)
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.serve_forever()
