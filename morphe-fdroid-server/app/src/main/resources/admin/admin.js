// Admin UI behaviour. Plain browser JavaScript, no build step.
//
// How it fits together:
//   - Every clickable thing carries data-action="name"; one click listener
//     looks the name up in `actions` (see "Actions" below). To add a button,
//     add the markup and one entry there.
//   - Lists are re-rendered from `state` with the html`` template tag, which
//     escapes every interpolated value unless it is itself html``.
//   - The two patch pages ("Patched apps" and "Patched TV apps") are the same
//     code run against two CHANNELS with their own API base paths.
'use strict';

// =============================================================================
// Helpers
// =============================================================================

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

class SafeHtml {
  constructor(value) { this.value = value; }
  toString() { return this.value; }
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function toHtml(value) {
  if (value == null || value === false) return '';
  if (value instanceof SafeHtml) return value.value;
  if (Array.isArray(value)) return value.map(toHtml).join('');
  return escapeHtml(value);
}

/** Tagged template: html`<b>${name}</b>` escapes `name`; nested html`` is kept as markup. */
function html(strings, ...values) {
  let out = strings[0];
  values.forEach((value, i) => { out += toHtml(value) + strings[i + 1]; });
  return new SafeHtml(out);
}

const icon = (name, cls = '') => html`<svg class="icon ${cls}"><use href="#i-${name}"/></svg>`;

class ApiError extends Error {}

/** fetch() wrapper: sends/parses JSON and throws ApiError with the server's message. */
async function api(method, url, body) {
  const options = { method, headers: {} };
  if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(url, options);
  } catch (e) {
    throw new ApiError("Couldn't reach the server");
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error || `Request failed (${res.status})`);
  return data;
}

function toast(message, kind = 'info') {
  const el = document.createElement('div');
  el.className = `toast is-${kind}`;
  el.textContent = message;
  $('#toasts').append(el);
  setTimeout(() => {
    el.classList.add('is-leaving');
    setTimeout(() => el.remove(), 250);
  }, kind === 'error' ? 7000 : 4000);
}

/** Disables `btn` and shows a spinner + `label` while `work()` runs. */
async function withBusy(btn, label, work) {
  const original = btn.innerHTML;
  btn.disabled = true;
  btn.innerHTML = toHtml(html`<span class="spinner"></span>${label}`);
  try {
    return await work();
  } finally {
    btn.disabled = false;
    btn.innerHTML = original;
  }
}

/** Shows an error toast instead of an unhandled rejection. */
function reportErrors(work) {
  return async (...args) => {
    try {
      await work(...args);
    } catch (e) {
      toast(e instanceof ApiError ? e.message : `Something went wrong: ${e.message}`, 'error');
      if (!(e instanceof ApiError)) console.error(e);
    }
  };
}

// ---- Dialogs ----

function openDialog(id, { title } = {}) {
  const dialog = document.getElementById(id);
  if (title) $('[data-text="title"]', dialog).textContent = title;
  setFormError(dialog, '');
  $$('[data-form-success]', dialog).forEach((el) => { el.textContent = ''; });
  $$('[data-filter]', dialog).forEach((el) => { el.value = ''; });
  if (!dialog.open) dialog.showModal();
  return dialog;
}

const closeDialog = (id) => document.getElementById(id).close();

function setFormError(root, message) {
  $$('[data-form-error]', root).forEach((el) => { el.textContent = message; });
}

/** Runs a dialog form's submit handler, showing ApiErrors inside the dialog instead of a toast. */
function onSubmit(form, handler) {
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    setFormError(form, '');
    const submitBtn = e.submitter || $('[type="submit"]', form);
    try {
      await withBusy(submitBtn, submitBtn.textContent.trim(), () => handler(form));
    } catch (err) {
      setFormError(form, err instanceof ApiError ? err.message : `Something went wrong: ${err.message}`);
      if (!(err instanceof ApiError)) console.error(err);
    }
  });
}

/** Promise-based replacement for window.confirm(). */
function confirmAction({ title, body = '', confirmLabel = 'Delete' }) {
  const dialog = document.getElementById('confirmDialog');
  $('[data-text="title"]', dialog).textContent = title;
  $('[data-text="body"]', dialog).textContent = body;
  $('[data-text="confirm"]', dialog).textContent = confirmLabel;
  dialog.returnValue = '';
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok'), { once: true });
  });
}

// ---- Formatting ----

function timeAgo(value) {
  const date = new Date(value);
  if (!value || Number.isNaN(date.getTime())) return value || '';
  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  const units = [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]];
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return rtf.format(-Math.round(seconds / size), unit);
  }
  return 'just now';
}

const when = (value) => html`<time datetime="${value}" title="${value ? new Date(value).toLocaleString() : ''}">${timeAgo(value)}</time>`;

function describeDuration(seconds) {
  if (!seconds || seconds <= 0) return '';
  const parts = [];
  for (const [label, size] of [['day', 86400], ['hour', 3600], ['minute', 60]]) {
    const n = Math.floor(seconds / size);
    if (n) { parts.push(`${n} ${label}${n === 1 ? '' : 's'}`); seconds -= n * size; }
  }
  return parts.length ? `Every ${parts.slice(0, 2).join(' ')}` : `Every ${seconds} seconds`;
}

const plural = (n, word, words = `${word}s`) => `${n} ${n === 1 ? word : words}`;

const slugify = (text) => text.toLowerCase().trim().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '');

const githubUrl = (repo) => 'https://github.com/' + repo.split('/').map(encodeURIComponent).join('/');

/** Readable "github.com/owner/repo" for a patch source URL. */
const shortUrl = (url) => url.replace(/^https?:\/\/(www\.)?/, '').replace(/\/+$/, '');

function emptyState({ iconName, title, text, action, actionLabel }) {
  return html`<li class="empty">
    ${icon(iconName)}
    <div class="empty-title">${title}</div>
    <div>${text}</div>
    ${action ? html`<button type="button" class="btn btn-primary" data-action="${action}">${icon('plus')}${actionLabel}</button>` : ''}
  </li>`;
}

function menu(items) {
  return html`<details class="menu">
    <summary class="btn btn-ghost btn-sm btn-icon" aria-label="More actions" title="More actions">${icon('more')}</summary>
    <div class="menu-list">${items}</div>
  </details>`;
}

// One package's declared versions from a .mpp, marking the ones only
// supported experimentally (patched only when an attachment opts in).
function describePackageVersions(pkg) {
  const experimental = new Set(pkg.experimental_versions || []);
  const listed = (pkg.versions || []).map((v) => (experimental.has(v) ? `${v} (experimental)` : v));
  if (pkg.any_version) return listed.length ? `all versions (listed: ${listed.join(', ')})` : 'all versions';
  if (pkg.any_version_experimental) listed.push(listed.length ? 'any other version (experimental)' : 'any version (experimental)');
  return listed.join(', ');
}

// =============================================================================
// State
// =============================================================================

const CHANNELS = {
  mobile: {
    key: 'mobile',
    page: 'patched',
    title: 'Patched apps',
    description: 'Apps downloaded from APKMirror, patched with Morphe, and published to your patched repo.',
    repoPath: '/patched/repo',
    apiLibrary: '/api/patch-library',
    apiTargets: '/api/patch-targets',
    library: [],
    targets: [],
  },
  tv: {
    key: 'tv',
    page: 'patched-tv',
    title: 'Patched TV apps',
    description: 'A separate set of apps and patch bundles for Android TV builds, so they don\'t clash with the phone builds of the same app.',
    repoPath: '/patched-tv/repo',
    apiLibrary: '/api/patch-library-tv',
    apiTargets: '/api/patch-targets-tv',
    library: [],
    targets: [],
  },
};

const state = {
  repos: [],
  settings: null,
  repoCheckPolling: false,
  activeChannel: CHANNELS.mobile, // channel the open dialog belongs to
};

const channelOf = (el) => CHANNELS[el.closest('[data-channel]')?.dataset.channel] || state.activeChannel;
const pageOf = (channel) => document.getElementById(`page-${channel.page}`);
const findTarget = (channel, id) => channel.targets.find((t) => t.id === id);
const findLibraryPatch = (channel, id) => channel.library.find((p) => p.id === id);

function setCount(page, n) {
  const el = $(`[data-count="${page}"]`);
  if (el) el.textContent = n ? String(n) : '';
}

// =============================================================================
// Navigation + theme
// =============================================================================

const PAGES = ['github', 'patched', 'patched-tv', 'settings'];

function showPage() {
  const requested = location.hash.slice(1);
  const page = PAGES.includes(requested) ? requested : 'github';
  $$('.page').forEach((el) => { el.hidden = el.dataset.page !== page; });
  $$('.nav-item').forEach((el) => {
    if (el.dataset.page === page) el.setAttribute('aria-current', 'page');
    else el.removeAttribute('aria-current');
  });
}
window.addEventListener('hashchange', showPage);

function currentTheme() {
  return document.documentElement.dataset.theme
    || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
}

// =============================================================================
// GitHub apps
// =============================================================================

async function loadRepos() {
  const data = await api('GET', '/api/repos');
  state.repos = data.repos;
  renderRepos();
}

function renderRepos() {
  const list = $('#repoList');
  setCount('github', state.repos.length);
  if (!state.repos.length) {
    list.innerHTML = toHtml(emptyState({
      iconName: 'github',
      title: 'No GitHub repos yet',
      text: 'Add a repo and its release APKs will show up in your F-Droid repo.',
      action: 'new-repo',
      actionLabel: 'Add repo',
    }));
    return;
  }
  list.innerHTML = toHtml(state.repos.map(renderRepoItem));
}

function renderRepoItem(repo) {
  const [owner, ...rest] = repo.repo.split('/');
  const name = rest.join('/');
  const latest = repo.checked_entries[0];
  const defaultPattern = state.settings?.apk_pattern;

  const latestLine = latest
    ? html`<span>Latest <strong>${latest.tag || latest.release_id}</strong> · checked ${when(latest.processed_at)}</span>`
    : html`<span>Nothing downloaded yet</span>`;

  const history = repo.checked_entries.length ? html`
    <details class="history">
      <summary>${plural(repo.checked_entries.length, 'checked release')} ${icon('chevron')}</summary>
      <ul class="history-list">${repo.checked_entries.map((entry) => html`
        <li class="history-row">
          <div class="history-main">
            ${entry.tag
              ? html`<a href="${githubUrl(repo.repo)}/releases/tag/${encodeURIComponent(entry.tag)}" target="_blank" rel="noopener"><strong>${entry.tag}</strong></a>`
              : html`<strong>${entry.release_id}</strong>`}
            ${entry.type && entry.type !== 'stable' ? html` <span class="badge">${entry.type}</span>` : ''}
            <span class="history-meta">
              · ${entry.apks_downloaded}/${entry.apks_found} APKs · ${when(entry.processed_at)}
            </span>
            ${entry.apks_downloaded < entry.apks_found ? html` <span class="badge badge-warning">incomplete</span>` : ''}
          </div>
          <button type="button" class="btn btn-ghost btn-sm" data-action="forget-release"
                  data-id="${repo.id}" data-release="${entry.build_number}"
                  title="Forget this release so the next check downloads it again">Forget</button>
        </li>`)}
      </ul>
    </details>` : '';

  return html`
    <li class="item ${repo.enabled ? '' : 'is-paused'}">
      <span class="avatar"><img src="https://github.com/${encodeURIComponent(owner)}.png?size=72" alt="" loading="lazy" /><span>${owner.charAt(0)}</span></span>
      <div class="item-body">
        <div class="item-title">
          <a href="${githubUrl(repo.repo)}" target="_blank" rel="noopener"><span class="muted">${owner}/</span>${name}</a>
          ${repo.enabled ? '' : html`<span class="badge badge-warning">Paused</span>`}
        </div>
        <div class="item-sub">${latestLine}</div>
        <div class="chips">
          <span class="badge">Checks newest ${repo.max_releases}</span>
          ${repo.include_prereleases ? html`<span class="badge">Pre-releases</span>` : ''}
          ${repo.include_drafts ? html`<span class="badge">Drafts</span>` : ''}
          ${defaultPattern && repo.apk_pattern !== defaultPattern ? html`<span class="badge mono" title="APK file pattern">${repo.apk_pattern}</span>` : ''}
        </div>
        ${history}
      </div>
      <div class="item-actions">
        <button type="button" class="btn btn-sm" data-action="check-repo" data-id="${repo.id}" data-check-btn
                ${repo.enabled ? '' : html`disabled title="Paused repos aren't checked"`}>
          ${icon('refresh')}Check now
        </button>
        <button type="button" class="btn btn-sm" data-action="edit-repo" data-id="${repo.id}">${icon('pencil')}Edit</button>
        ${menu(html`<a href="${githubUrl(repo.repo)}/releases" target="_blank" rel="noopener">${icon('external')}Releases on GitHub</a>`)}
      </div>
    </li>`;
}

function openRepoDialog(repo) {
  const s = state.settings || {};
  $('#repoId').value = repo ? repo.id : '';
  $('#repo').value = repo ? repo.repo : '';
  $('#max_releases').value = repo ? repo.max_releases : (s.default_max_releases ?? 5);
  $('#apk_pattern').value = repo ? repo.apk_pattern : (s.apk_pattern || '.*\\.apk$');
  $('#include_prereleases').checked = repo ? repo.include_prereleases : (s.default_include_prereleases ?? true);
  $('#include_drafts').checked = repo ? repo.include_drafts : (s.default_include_drafts ?? false);
  $('#enabled').checked = repo ? repo.enabled : (s.default_enabled ?? true);
  openDialog('repoDialog', { title: repo ? 'Edit repo' : 'Add repo' });
}

onSubmit($('#repoForm'), async () => {
  const id = $('#repoId').value;
  const payload = {
    repo: $('#repo').value.trim().replace(/^https?:\/\/github\.com\//, '').replace(/\/+$/, ''),
    max_releases: Number($('#max_releases').value),
    apk_pattern: $('#apk_pattern').value,
    include_prereleases: $('#include_prereleases').checked,
    include_drafts: $('#include_drafts').checked,
    enabled: $('#enabled').checked,
  };
  if (id) await api('PUT', `/api/repos/${id}`, payload);
  else await api('POST', '/api/repos', payload);
  closeDialog('repoDialog');
  toast(id ? 'Repo saved' : `Added ${payload.repo}`, 'success');
  await loadRepos();
});

// ---- "Check now" (runs in the background; we poll until it finishes) ----

function setCheckButtonsDisabled(disabled) {
  $$('[data-check-btn]').forEach((btn) => {
    const repo = state.repos.find((r) => String(r.id) === btn.dataset.id);
    btn.disabled = disabled || (repo && !repo.enabled);
  });
}

function showCheckStatus(message, kind = '') {
  const banner = $('#repoCheckStatus');
  banner.hidden = !message;
  banner.className = `banner ${kind ? `is-${kind}` : ''}`;
  banner.innerHTML = toHtml(kind ? message : html`<span class="spinner"></span>${message}`);
}

async function startRepoCheck(url, label) {
  await api('POST', url);
  showCheckStatus(`Checking ${label} for new releases…`);
  pollRepoCheck();
}

async function pollRepoCheck() {
  if (state.repoCheckPolling) return;
  state.repoCheckPolling = true;
  setCheckButtonsDisabled(true);
  try {
    while (true) {
      const status = await api('GET', '/api/repos/check-status');
      const label = status.repo || 'all repos';
      if (!status.running) {
        if (status.error) {
          showCheckStatus(`Checking ${label} failed: ${status.error}`, 'error');
        } else if (status.finished_at) {
          if (!status.downloaded) showCheckStatus(`${label}: no new releases.`, 'success');
          else if (status.index_updated) showCheckStatus(`${label}: downloaded ${plural(status.downloaded, 'new APK')} and updated the F-Droid index.`, 'success');
          else showCheckStatus(`${label}: downloaded ${plural(status.downloaded, 'new APK')}, but updating the F-Droid index failed (see logs).`, 'error');
        }
        await loadRepos();
        break;
      }
      showCheckStatus(`Checking ${label} for new releases…`);
      await new Promise((resolve) => setTimeout(resolve, 3000));
    }
  } catch (e) {
    showCheckStatus('Lost track of the check. Reload the page to see the result.', 'error');
  } finally {
    state.repoCheckPolling = false;
    setCheckButtonsDisabled(false);
  }
}

// =============================================================================
// Patched apps (both channels)
// =============================================================================

function buildPatchPages() {
  const template = $('#tpl-patch-page');
  for (const channel of Object.values(CHANNELS)) {
    const page = pageOf(channel);
    page.append(template.content.cloneNode(true));
    for (const key of ['title', 'description', 'repoPath']) {
      $$(`[data-text="${key}"]`, page).forEach((el) => { el.textContent = channel[key]; });
    }
  }
}

async function loadChannel(channel) {
  const [library, targets] = await Promise.all([api('GET', channel.apiLibrary), api('GET', channel.apiTargets)]);
  channel.library = library.library;
  channel.targets = targets.targets;
  renderChannel(channel);
}

function renderChannel(channel) {
  renderTargets(channel);
  renderLibrary(channel);
  setCount(channel.page, channel.targets.length);
}

// ---- Apps (patch targets) ----

function renderTargets(channel) {
  const list = $('[data-list="targets"]', pageOf(channel));
  if (!channel.targets.length) {
    list.innerHTML = toHtml(emptyState({
      iconName: channel.key === 'tv' ? 'tv' : 'phone',
      title: 'No apps yet',
      text: channel.library.length ? 'Add an app from APKMirror, then attach a patch bundle to it.' : 'Start by adding a patch bundle below, then add the app you want to patch.',
      action: 'new-target',
      actionLabel: 'Add app',
    }));
    return;
  }
  list.innerHTML = toHtml(channel.targets.map((target) => renderTargetItem(channel, target)));
}

// History entries whose output starts with this were not published (the APK can't run
// on this repo's devices); see PatchUpdatePolicy.SKIPPED_PREFIX on the server.
const SKIPPED_PREFIX = 'skipped: ';
const isSkipped = (entry) => (entry.output || '').startsWith(SKIPPED_PREFIX);

function renderTargetItem(channel, target) {
  const latest = target.checked_entries.find((entry) => !isSkipped(entry));
  const attached = target.patches.length
    ? target.patches.map((a) => html`<span class="badge badge-accent" title="App versions: ${(a.supported_versions || []).length ? a.supported_versions.join(', ') : 'whatever the bundle supports'}">${icon('layers', 'icon-inline')} ${a.patch_name}${(a.supported_versions || []).length ? html` · ${a.supported_versions.join(', ')}` : ''}</span>`)
    : html`<span class="badge badge-warning">No patches attached</span>`;

  const history = target.checked_entries.length ? html`
    <details class="history">
      <summary>${plural(target.checked_entries.length, 'patched build')} ${icon('chevron')}</summary>
      <ul class="history-list">${target.checked_entries.map((entry) => html`
        <li class="history-row">
          <div class="history-main">
            <strong>${entry.version}</strong>
            ${isSkipped(entry) ? html` <span class="badge badge-warning">Skipped</span>` : ''}
            <span class="history-meta">· ${entry.patch_id}${entry.patch_version ? ` ${entry.patch_version}` : ''} · ${when(entry.processed_at)}</span>
            ${entry.output ? html`<div class="history-path">${isSkipped(entry) ? entry.output.slice(SKIPPED_PREFIX.length) : entry.output}</div>` : ''}
          </div>
          <button type="button" class="btn btn-ghost btn-sm" data-action="forget-build"
                  data-id="${target.id}" data-cache-key="${entry.cache_key}"
                  title="Forget this entry so the version can be patched again">Forget</button>
        </li>`)}
      </ul>
    </details>` : '';

  return html`
    <li class="item ${target.enabled ? '' : 'is-paused'}">
      <span class="avatar">${target.name.charAt(0)}</span>
      <div class="item-body">
        <div class="item-title">
          ${target.name}
          ${target.enabled ? '' : html`<span class="badge badge-warning">Paused</span>`}
        </div>
        <div class="item-sub">
          ${latest ? html`<span>Latest build <strong>${latest.version}</strong> · ${when(latest.processed_at)}</span>` : html`<span>Not patched yet</span>`}
          <a href="${target.apkmirror_url}" target="_blank" rel="noopener">APKMirror ${icon('external', 'icon-inline')}</a>
          ${target.apkpure_url ? html`<a href="${target.apkpure_url}" target="_blank" rel="noopener">APKPure ${icon('external', 'icon-inline')}</a>` : ''}
          ${target.package_name ? html`<span class="mono">${target.package_name}</span>` : ''}
        </div>
        <div class="chips">${attached}</div>
        ${history}
      </div>
      <div class="item-actions">
        <button type="button" class="btn btn-sm" data-action="patch-target" data-id="${target.id}">${icon('play')}Patch now</button>
        <button type="button" class="btn btn-sm" data-action="manage-patches" data-id="${target.id}">${icon('layers')}Patches</button>
        ${menu(html`
          <button type="button" data-action="patch-version" data-id="${target.id}">${icon('play')}Patch a specific version…</button>
          <button type="button" data-action="edit-target" data-id="${target.id}">${icon('pencil')}Edit app</button>
          <hr />
          <button type="button" class="is-danger" data-action="delete-target" data-id="${target.id}">${icon('trash')}Delete app</button>
        `)}
      </div>
    </li>`;
}

function openTargetDialog(channel, target) {
  state.activeChannel = channel;
  $('#targetOriginalId').value = target ? target.id : '';
  $('#targetId').value = target ? target.id : '';
  $('#targetId').disabled = !!target;
  $('#targetName').value = target ? target.name : '';
  $('#targetApkmirrorUrl').value = target ? target.apkmirror_url : '';
  $('#targetApkpureUrl').value = target ? target.apkpure_url || '' : '';
  $('#targetPackageName').value = target ? target.package_name || '' : '';
  $('#targetEnabled').checked = target ? target.enabled : true;
  openDialog('targetDialog', { title: target ? `Edit ${target.name}` : 'Add app' });
}

onSubmit($('#targetForm'), async () => {
  const channel = state.activeChannel;
  const originalId = $('#targetOriginalId').value;
  const payload = {
    id: $('#targetId').value.trim(),
    name: $('#targetName').value.trim(),
    apkmirror_url: $('#targetApkmirrorUrl').value.trim(),
    apkpure_url: $('#targetApkpureUrl').value.trim(),
    package_name: $('#targetPackageName').value.trim(),
    enabled: $('#targetEnabled').checked,
  };
  if (originalId) await api('PUT', `${channel.apiTargets}/${originalId}`, payload);
  else await api('POST', channel.apiTargets, payload);
  closeDialog('targetDialog');
  toast(originalId ? 'App saved' : `Added ${payload.name}`, 'success');
  await loadChannel(channel);
  if (!originalId) openAttachDialog(channel, payload.id); // next step for a new app: attach a bundle
});

// ---- Patch library ----

function renderLibrary(channel) {
  const list = $('[data-list="library"]', pageOf(channel));
  if (!channel.library.length) {
    list.innerHTML = toHtml(emptyState({
      iconName: 'box',
      title: 'No patch bundles yet',
      text: 'Add a Morphe .mpp bundle by linking its GitHub/GitLab repo or uploading the file.',
      action: 'new-library-patch',
      actionLabel: 'Add patch bundle',
    }));
    return;
  }
  list.innerHTML = toHtml(channel.library.map((patch) => renderLibraryItem(channel, patch)));
}

function renderLibraryItem(channel, patch) {
  const usedBy = channel.targets.filter((t) => t.patches.some((a) => a.patch_id === patch.id));
  return html`
    <li class="item">
      <span class="avatar">${icon('box')}</span>
      <div class="item-body">
        <div class="item-title">
          ${patch.name}
          ${patch.version ? html`<span class="badge">${patch.version}</span>` : ''}
        </div>
        <div class="item-sub">
          ${patch.source_url
            ? html`<a href="${patch.source_url}" target="_blank" rel="noopener">${icon(patch.source_provider === 'github' ? 'github' : 'refresh', 'icon-inline')} ${shortUrl(patch.source_url)}</a>
                   <span>Auto-updates${patch.include_prereleases ? ' (incl. pre-releases)' : ''}</span>`
            : html`<span>Uploaded by hand</span>`}
          ${patch.file ? html`<span class="mono">${patch.file}</span>` : ''}
          ${patch.updated_at ? html`<span>Updated ${when(patch.updated_at)}</span>` : ''}
        </div>
        <div class="chips">
          ${usedBy.length
            ? html`<span class="badge" title="${usedBy.map((t) => t.name).join(', ')}">Used by ${plural(usedBy.length, 'app')}</span>`
            : html`<span class="badge">Not attached to any app</span>`}
        </div>
      </div>
      <div class="item-actions">
        ${patch.source_url ? html`<button type="button" class="btn btn-sm" data-action="check-library-source" data-id="${patch.id}">${icon('refresh')}Check for update</button>` : ''}
        <button type="button" class="btn btn-sm" data-action="edit-library-patch" data-id="${patch.id}">${icon('pencil')}Edit</button>
        ${menu(html`
          <button type="button" data-action="inspect-library-patch" data-id="${patch.id}">${icon('eye')}See what it patches</button>
          <hr />
          <button type="button" class="is-danger" data-action="delete-library-patch" data-id="${patch.id}">${icon('trash')}Delete bundle</button>
        `)}
      </div>
    </li>`;
}

function readFileAsBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result.split(',')[1]);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

function openLibraryDialog(channel, patch) {
  state.activeChannel = channel;
  $('#libraryOriginalId').value = patch ? patch.id : '';
  $('#libraryId').value = patch ? patch.id : '';
  $('#libraryId').disabled = !!patch;
  $('#libraryName').value = patch ? patch.name : '';
  $('#libraryFile').value = '';
  $('#libraryFileHint').textContent = patch ? 'Leave empty to keep the current file.' : 'Only needed if you don\'t link a repo above.';
  $('#libraryVersion').value = patch ? patch.version || '' : '';
  $('#librarySourceUrl').value = patch ? patch.source_url || '' : '';
  $('#libraryIncludePrereleases').checked = patch ? !!patch.include_prereleases : false;
  $('#libraryCheckSourceBtn').hidden = !(patch && patch.source_url);
  openDialog('libraryDialog', { title: patch ? `Edit ${patch.name}` : 'Add patch bundle' });
}

onSubmit($('#libraryForm'), async () => {
  const channel = state.activeChannel;
  const originalId = $('#libraryOriginalId').value;
  const file = $('#libraryFile').files[0];
  const sourceUrl = $('#librarySourceUrl').value.trim();
  if (!originalId && !file && !sourceUrl) throw new ApiError('Link a GitHub/GitLab repo or choose a .mpp file.');

  const payload = {
    id: $('#libraryId').value.trim(),
    name: $('#libraryName').value.trim(),
    version: $('#libraryVersion').value.trim(),
    source_url: sourceUrl,
    include_prereleases: $('#libraryIncludePrereleases').checked,
  };
  if (file) {
    payload.filename = file.name;
    payload.content_base64 = await readFileAsBase64(file);
  }
  if (originalId) await api('PUT', `${channel.apiLibrary}/${originalId}`, payload);
  else await api('POST', channel.apiLibrary, payload);
  closeDialog('libraryDialog');
  toast(originalId ? 'Patch bundle saved' : `Added ${payload.name}`, 'success');
  await loadChannel(channel);
});

$('#libraryCheckSourceBtn').addEventListener('click', reportErrors(async (e) => {
  const channel = state.activeChannel;
  const id = $('#libraryOriginalId').value;
  const dialog = $('#libraryDialog');
  setFormError(dialog, '');
  await withBusy(e.currentTarget, 'Checking…', async () => {
    try {
      const result = await api('POST', `${channel.apiLibrary}/${id}/check-source`);
      $('[data-form-success]', dialog).textContent = result.updated ? 'Imported a newer release.' : 'Already up to date.';
    } catch (err) {
      setFormError(dialog, err.message);
    }
    await loadChannel(channel);
    const refreshed = findLibraryPatch(channel, id);
    if (refreshed) $('#libraryVersion').value = refreshed.version || '';
  });
}));

// ---- Bundle contents ("See what it patches") ----

async function openInspectDialog(channel, patch) {
  state.activeChannel = channel;
  const dialog = openDialog('inspectDialog', { title: patch.name });
  const body = $('#inspectBody');
  body.innerHTML = toHtml(html`<p class="muted">Reading the bundle…</p>`);
  let data;
  try {
    data = await api('GET', `${channel.apiLibrary}/${patch.id}/inspect`);
  } catch (e) {
    body.innerHTML = '';
    setFormError(dialog, e.message);
    return;
  }
  if (!data.patches.length) {
    body.innerHTML = toHtml(html`<p class="muted">This bundle doesn't contain any patches.</p>`);
    return;
  }

  // One group per app package: which patches support it, with which versions.
  const UNRESTRICTED = 'Any app';
  const byPackage = {};
  for (const p of data.patches) {
    const entries = p.packages.length ? p.packages : [{ package: UNRESTRICTED, versions: [], any_version: true }];
    for (const pkg of entries) {
      (byPackage[pkg.package] ||= []).push({ name: p.name || '(unnamed patch)', description: p.description, versions: describePackageVersions(pkg) });
    }
  }
  const names = Object.keys(byPackage).sort((a, b) => (a === UNRESTRICTED) - (b === UNRESTRICTED) || a.localeCompare(b));
  body.innerHTML = toHtml(names.map((pkg) => html`
    <details class="group" data-filter-text="${pkg} ${byPackage[pkg].map((e) => e.name).join(' ')}">
      <summary><span class="mono">${pkg}</span><span class="badge">${plural(byPackage[pkg].length, 'patch', 'patches')}</span>${icon('chevron')}</summary>
      <div class="group-body">${byPackage[pkg].map((entry) => html`
        <div class="group-row">
          <strong>${entry.name}</strong> <span class="muted">· ${entry.versions}</span>
          ${entry.description ? html`<div class="muted">${entry.description}</div>` : ''}
        </div>`)}
      </div>
    </details>`));
}

// ---- Attached bundles ("Patches" button) ----

function openAttachDialog(channel, targetId) {
  state.activeChannel = channel;
  const target = findTarget(channel, targetId);
  $('#attachTargetId').value = targetId;
  $('#attachForm').reset();
  $('#attachTargetId').value = targetId;
  openDialog('attachDialog', { title: `Patches for ${target ? target.name : targetId}` });
  renderAttachDialog();
}

function renderAttachDialog() {
  const channel = state.activeChannel;
  const targetId = $('#attachTargetId').value;
  const target = findTarget(channel, targetId);
  const attachments = target ? target.patches : [];

  $('#attachList').innerHTML = toHtml(attachments.length
    ? attachments.map((a) => {
      const customised = Object.keys(a.patch_selection || {}).length + Object.keys(a.option_overrides || {}).length;
      const versions = (a.supported_versions || []).length ? a.supported_versions.join(', ') : 'whatever the bundle supports';
      return html`
        <li class="item">
          <div class="item-body">
            <div class="item-title">${a.patch_name}${a.patch_version ? html` <span class="badge">${a.patch_version}</span>` : ''}</div>
            <div class="item-sub">
              <span>Versions: ${versions}</span>
              ${a.patch_args ? html`<span>Args: <span class="mono">${a.patch_args}</span></span>` : ''}
            </div>
            <div class="chips">
              ${customised ? html`<span class="badge badge-accent">${plural(customised, 'custom setting')}</span>` : ''}
              ${!(a.supported_versions || []).length && a.include_experimental_versions ? html`<span class="badge">Includes experimental versions</span>` : ''}
            </div>
          </div>
          <div class="item-actions">
            <button type="button" class="btn btn-sm" data-action="configure-attachment" data-id="${a.patch_id}">${icon('sliders')}Configure</button>
            <button type="button" class="btn btn-sm btn-ghost" data-action="detach-patch" data-id="${a.patch_id}">Detach</button>
          </div>
        </li>`;
    })
    : html`<li class="empty"><div class="empty-title">No bundles attached</div><div>This app won't be patched until you attach one below.</div></li>`);

  const attachedIds = new Set(attachments.map((a) => a.patch_id));
  const available = channel.library.filter((p) => !attachedIds.has(p.id));
  const select = $('#attachPatchId');
  select.innerHTML = toHtml(available.map((p) => html`<option value="${p.id}">${p.name}${p.version ? ` (${p.version})` : ''}</option>`));
  $('#attachForm').hidden = !available.length;
  $('#attachNote').textContent = available.length ? ''
    : channel.library.length ? 'Every bundle in the patch library is attached.' : 'Add a patch bundle to the patch library first, then attach it here.';
}

onSubmit($('#attachForm'), async () => {
  const channel = state.activeChannel;
  const targetId = $('#attachTargetId').value;
  await api('POST', `${channel.apiTargets}/${targetId}/attachments`, {
    patch_id: $('#attachPatchId').value,
    supported_versions: $('#attachSupportedVersions').value.trim(),
    patch_args: $('#attachPatchArgs').value.trim(),
    include_experimental_versions: $('#attachIncludeExperimental').checked,
  });
  $('#attachForm').reset();
  $('#attachTargetId').value = targetId;
  await loadChannel(channel);
  renderAttachDialog();
  toast('Bundle attached', 'success');
});

// ---- Configure one attachment's individual patches + options ----

let configureState = null; // { targetId, patchId, subPatches }

const subPatchDefaultEnabled = (subPatch) => subPatch.enabled !== false; // missing "Enabled:" means on

async function openConfigureDialog(targetId, patchId) {
  const channel = state.activeChannel;
  const target = findTarget(channel, targetId);
  const attachment = (target ? target.patches : []).find((a) => a.patch_id === patchId) || {};
  const dialog = openDialog('configureDialog', { title: `Configure ${attachment.patch_name || patchId}` });
  const body = $('#configureBody');
  const saveBtn = $('#configureSaveBtn');
  configureState = null;
  saveBtn.disabled = true;
  body.innerHTML = toHtml(html`<p class="muted">Reading the bundle…</p>`);

  let data;
  try {
    data = await api('GET', `${channel.apiLibrary}/${patchId}/inspect`);
  } catch (e) {
    body.innerHTML = '';
    setFormError(dialog, e.message);
    return;
  }

  // Only patches that apply to this app: unrestricted ones, or ones listing its package.
  const packageName = target ? target.package_name : '';
  const subPatches = packageName
    ? data.patches.filter((p) => !p.packages.length || p.packages.some((pkg) => pkg.package === packageName))
    : data.patches;
  if (!subPatches.length) {
    body.innerHTML = toHtml(html`<p class="muted">Nothing in this bundle applies to ${packageName || 'this app'}.</p>`);
    return;
  }

  configureState = { targetId, patchId, subPatches };
  saveBtn.disabled = false;
  const selection = attachment.patch_selection || {};
  const overrides = attachment.option_overrides || {};

  body.innerHTML = toHtml(subPatches.map((p, idx) => {
    const isDefaultOn = subPatchDefaultEnabled(p);
    const on = Object.prototype.hasOwnProperty.call(selection, p.name) ? selection[p.name] : isDefaultOn;
    const values = overrides[p.name] || {};
    return html`
      <div class="subpatch" data-subpatch="${idx}" data-filter-text="${p.name} ${p.description || ''}">
        <div class="subpatch-head">
          <label class="switch">
            <input type="checkbox" data-subpatch-toggle="${idx}" ${on ? 'checked' : ''} />
            <span class="switch-track"></span>
            <strong>${p.name || '(unnamed)'}</strong>
          </label>
          <span class="badge">${isDefaultOn ? 'On by default' : 'Off by default'}</span>
        </div>
        ${p.description ? html`<div class="subpatch-desc">${p.description}</div>` : ''}
        ${p.options.length ? html`<div class="subpatch-options">${p.options.map((opt) => {
          const listId = opt.possible_values ? `opt-${idx}-${slugify(opt.key)}` : '';
          return html`
            <div class="field">
              <label>${opt.title || opt.key}</label>
              <input type="text" data-option-for="${idx}" data-option-key="${opt.key}"
                     value="${values[opt.key] !== undefined ? values[opt.key] : ''}"
                     placeholder="${opt.default ? `Default: ${opt.default}` : 'No default'}"
                     ${listId ? html`list="${listId}"` : ''} />
              ${listId ? html`<datalist id="${listId}">${Object.entries(opt.possible_values).map(([label, value]) => html`<option value="${value}">${label}</option>`)}</datalist>` : ''}
              ${opt.description ? html`<p class="help">${opt.description}</p>` : ''}
            </div>`;
        })}</div>` : ''}
      </div>`;
  }));
  $$('[data-subpatch]', body).forEach(refreshSubPatchState);
}

/** Dims options of patches that are off and outlines anything changed from the default. */
function refreshSubPatchState(row) {
  if (!configureState) return;
  const p = configureState.subPatches[Number(row.dataset.subpatch)];
  const on = $('[data-subpatch-toggle]', row).checked;
  const optionChanged = $$('[data-option-key]', row).some((input) => {
    const value = input.value.trim();
    const opt = p.options.find((o) => o.key === input.dataset.optionKey);
    return value !== '' && value !== (opt.default || '');
  });
  row.classList.toggle('is-off', !on);
  row.classList.toggle('is-changed', on !== subPatchDefaultEnabled(p) || optionChanged);
}

$('#configureBody').addEventListener('input', (e) => {
  const row = e.target.closest('[data-subpatch]');
  if (row) refreshSubPatchState(row);
});

$('#configureSaveBtn').addEventListener('click', reportErrors(async (e) => {
  if (!configureState) return;
  const channel = state.activeChannel;
  const { targetId, patchId, subPatches } = configureState;
  const body = $('#configureBody');
  const patchSelection = {};
  const optionOverrides = {};

  subPatches.forEach((p, idx) => {
    const on = $(`[data-subpatch-toggle="${idx}"]`, body).checked;
    if (on !== subPatchDefaultEnabled(p)) patchSelection[p.name] = on;
    p.options.forEach((opt) => {
      const input = $(`[data-option-for="${idx}"][data-option-key="${CSS.escape(opt.key)}"]`, body);
      const value = input ? input.value.trim() : '';
      if (value !== '' && value !== (opt.default || '')) {
        (optionOverrides[p.name] ||= {})[opt.key] = value;
      }
    });
  });

  const dialog = $('#configureDialog');
  setFormError(dialog, '');
  try {
    await withBusy(e.currentTarget, 'Saving…', () => api('POST', `${channel.apiTargets}/${targetId}/attachments`, {
      patch_id: patchId, patch_selection: patchSelection, option_overrides: optionOverrides,
    }));
  } catch (err) {
    setFormError(dialog, err.message);
    return;
  }
  dialog.close();
  toast('Patch settings saved', 'success');
  await loadChannel(channel);
  if ($('#attachDialog').open) renderAttachDialog();
}));

// ---- Patch a specific version ----

function openVersionDialog(channel, target) {
  state.activeChannel = channel;
  $('#versionTargetId').value = target.id;
  $('#versionNumber').value = '';
  $('#versionPageUrl').value = '';
  openDialog('versionDialog', { title: `Patch a specific version of ${target.name}` });
}

onSubmit($('#versionForm'), async () => {
  const channel = state.activeChannel;
  const targetId = $('#versionTargetId').value;
  const result = await api('POST', `${channel.apiTargets}/${targetId}/run-version`, {
    version: $('#versionNumber').value.trim(),
    version_page_url: $('#versionPageUrl').value.trim(),
  });
  closeDialog('versionDialog');
  toast(result.updated ? 'Patched and published' : 'Nothing new was published. Check the logs for details.', result.updated ? 'success' : 'info');
  await loadChannel(channel);
});

// =============================================================================
// Settings
// =============================================================================

// Each entry: [element id, settings key, kind]. Adding a setting = one line here + its markup.
const SETTINGS_FIELDS = [
  ['settingsRepoName', 'repo_name', 'text'],
  ['settingsRepoDescription', 'repo_description', 'text'],
  ['settingsRepoIcon', 'repo_icon', 'text'],
  ['settingsGithubToken', 'github_token', 'text'],
  ['settingsUpdateInterval', 'update_interval', 'number'],
  ['settingsPatchCheckInterval', 'patch_check_interval', 'optional-number'],
  ['settingsApkPattern', 'apk_pattern', 'raw'],
  ['settingsMaxVersionsPerApp', 'max_versions_per_app', 'number'],
  ['settingsLogLevel', 'log_level', 'select'],
  ['settingsFlareSolverrUrl', 'flare_solverr_url', 'text'],
  ['settingsDefaultIncludePrereleases', 'default_include_prereleases', 'checkbox'],
  ['settingsDefaultIncludeDrafts', 'default_include_drafts', 'checkbox'],
  ['settingsDefaultEnabled', 'default_enabled', 'checkbox'],
  ['settingsDefaultMaxReleases', 'default_max_releases', 'number'],
];

async function loadSettings() {
  const data = await api('GET', '/api/settings');
  state.settings = data.settings;
  fillSettings();
}

function fillSettings() {
  const s = state.settings;
  for (const [id, key, kind] of SETTINGS_FIELDS) {
    const el = document.getElementById(id);
    if (kind === 'checkbox') el.checked = !!s[key];
    else if (kind === 'select') {
      const value = s[key] || '';
      if (value && ![...el.options].some((o) => o.value === value)) el.add(new Option(value, value));
      el.value = value;
    } else el.value = s[key] ?? '';
  }

  const base = (s.repo_url || '').replace(/\/+$/, '');
  const urls = [['Main', '/repo'], ['Patched', '/patched/repo'], ['Patched TV', '/patched-tv/repo']];
  $('#settingsRepoUrls').innerHTML = toHtml(base
    ? urls.map(([label, path]) => html`
      <div class="url-row">
        <span class="url-label">${label}</span>
        <code title="${base + path}">${base + path}</code>
        <button type="button" class="btn btn-ghost btn-sm btn-icon" data-action="copy" data-value="${base + path}" title="Copy">${icon('copy')}</button>
      </div>`)
    : html`<span class="muted">Not set</span>`);

  document.title = s.repo_name ? `${s.repo_name} · Admin` : 'Repo Config';
  $('#brandName').textContent = s.repo_name || 'F-Droid repo';
  refreshDurations();
  markSettingsDirty(false);
}

function collectSettings() {
  const payload = {};
  for (const [id, key, kind] of SETTINGS_FIELDS) {
    const el = document.getElementById(id);
    if (kind === 'checkbox') payload[key] = el.checked;
    else if (kind === 'number') payload[key] = Number(el.value);
    else if (kind === 'optional-number') payload[key] = el.value.trim() === '' ? null : Number(el.value);
    else if (kind === 'raw') payload[key] = el.value;
    else payload[key] = el.value.trim();
  }
  return payload;
}

function refreshDurations() {
  $$('[data-duration]').forEach((input) => {
    const help = $(`[data-duration-for="${input.id}"]`);
    const fallback = input.id === 'settingsPatchCheckInterval' ? 'Uses the GitHub interval' : '';
    help.textContent = describeDuration(Number(input.value)) || fallback;
  });
}

function markSettingsDirty(dirty) {
  $('.savebar').classList.toggle('is-dirty', dirty);
  $('#settingsDirty').textContent = dirty ? 'You have unsaved changes' : 'All changes saved';
}

$('#settingsForm').addEventListener('input', () => {
  refreshDurations();
  markSettingsDirty(true);
});

onSubmit($('#settingsForm'), async () => {
  const data = await api('PUT', '/api/settings', collectSettings());
  state.settings = data.settings;
  fillSettings();
  toast('Settings saved', 'success');
  await loadRepos();
});

// =============================================================================
// Actions (data-action="...")
// =============================================================================

const actions = {
  'toggle-theme': () => {
    const next = currentTheme() === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('admin-theme', next); } catch (e) { /* private mode */ }
  },
  'toggle-secret': (el) => {
    const input = document.getElementById(el.dataset.target);
    input.type = input.type === 'password' ? 'text' : 'password';
    el.textContent = input.type === 'password' ? 'Show' : 'Hide';
  },
  copy: async (el) => {
    await navigator.clipboard.writeText(el.dataset.value);
    toast('Copied', 'success');
  },
  'reset-settings': () => fillSettings(),

  // GitHub apps
  'new-repo': () => openRepoDialog(null),
  'edit-repo': (el) => openRepoDialog(state.repos.find((r) => String(r.id) === el.dataset.id)),
  'check-all-repos': () => startRepoCheck('/api/repos/check', 'all repos'),
  'check-repo': (el) => {
    const repo = state.repos.find((r) => String(r.id) === el.dataset.id);
    return startRepoCheck(`/api/repos/${repo.id}/check`, repo.repo);
  },
  'forget-release': async (el) => {
    await api('DELETE', `/api/repos/${el.dataset.id}/checked/${encodeURIComponent(el.dataset.release)}`);
    toast('Release forgotten. The next check will download it again.', 'success');
    await loadRepos();
  },

  // Patched apps
  'new-target': (el) => openTargetDialog(channelOf(el), null),
  'edit-target': (el) => { const c = channelOf(el); openTargetDialog(c, findTarget(c, el.dataset.id)); },
  'manage-patches': (el) => openAttachDialog(channelOf(el), el.dataset.id),
  'patch-version': (el) => { const c = channelOf(el); openVersionDialog(c, findTarget(c, el.dataset.id)); },
  'patch-target': async (el) => {
    const channel = channelOf(el);
    const target = findTarget(channel, el.dataset.id);
    const result = await withBusy(el, 'Patching…', () => api('POST', `${channel.apiTargets}/${target.id}/run`));
    toast(result.updated ? `Published a new build of ${target.name}` : `Nothing new to publish for ${target.name}`, result.updated ? 'success' : 'info');
    await loadChannel(channel);
  },
  'patch-all': async (el) => {
    const channel = channelOf(el);
    const result = await withBusy(el, 'Patching…', () => api('POST', `${channel.apiTargets}/run-all`));
    toast(result.updated ? 'Published new builds' : 'Everything is up to date', result.updated ? 'success' : 'info');
    await loadChannel(channel);
  },
  'delete-target': async (el) => {
    const channel = channelOf(el);
    const target = findTarget(channel, el.dataset.id);
    const ok = await confirmAction({ title: `Delete ${target.name}?`, body: 'It stops being watched and patched. Builds already published stay in the repo.', confirmLabel: 'Delete app' });
    if (!ok) return;
    await api('DELETE', `${channel.apiTargets}/${target.id}`);
    toast(`Deleted ${target.name}`, 'success');
    await loadChannel(channel);
  },
  'forget-build': async (el) => {
    const channel = channelOf(el);
    await api('DELETE', `${channel.apiTargets}/${el.dataset.id}/checked/${encodeURIComponent(el.dataset.cacheKey)}`);
    toast('Forgotten. That version can be patched again.', 'success');
    await loadChannel(channel);
  },

  // Patch library
  'new-library-patch': (el) => openLibraryDialog(channelOf(el), null),
  'edit-library-patch': (el) => { const c = channelOf(el); openLibraryDialog(c, findLibraryPatch(c, el.dataset.id)); },
  'inspect-library-patch': (el) => { const c = channelOf(el); openInspectDialog(c, findLibraryPatch(c, el.dataset.id)); },
  'check-library-source': async (el) => {
    const channel = channelOf(el);
    const patch = findLibraryPatch(channel, el.dataset.id);
    const result = await withBusy(el, 'Checking…', () => api('POST', `${channel.apiLibrary}/${patch.id}/check-source`));
    toast(result.updated ? `Imported a newer ${patch.name}` : `${patch.name} is already up to date`, result.updated ? 'success' : 'info');
    await loadChannel(channel);
  },
  'delete-library-patch': async (el) => {
    const channel = channelOf(el);
    const patch = findLibraryPatch(channel, el.dataset.id);
    const ok = await confirmAction({ title: `Delete ${patch.name}?`, body: 'It will be detached from every app using it.', confirmLabel: 'Delete bundle' });
    if (!ok) return;
    await api('DELETE', `${channel.apiLibrary}/${patch.id}`);
    toast(`Deleted ${patch.name}`, 'success');
    await loadChannel(channel);
  },

  // Inside the "Patches" dialog
  'configure-attachment': (el) => openConfigureDialog($('#attachTargetId').value, el.dataset.id),
  'detach-patch': async (el) => {
    const channel = state.activeChannel;
    await api('DELETE', `${channel.apiTargets}/${$('#attachTargetId').value}/attachments/${el.dataset.id}`);
    await loadChannel(channel);
    renderAttachDialog();
    toast('Bundle detached', 'success');
  },
};

document.addEventListener('click', (e) => {
  // Close open overflow menus when clicking anywhere else.
  $$('details.menu[open]').forEach((m) => { if (!m.contains(e.target) || e.target.closest('.menu-list > *')) m.open = false; });

  const el = e.target.closest('[data-action]');
  if (el && !el.disabled && actions[el.dataset.action]) {
    e.preventDefault();
    reportErrors(actions[el.dataset.action])(el, e);
  }

  // Click on a dialog's backdrop or a [data-close] button closes it.
  if (e.target.matches('dialog')) e.target.close();
  const closer = e.target.closest('[data-close]');
  if (closer) closer.closest('dialog').close();
});

// Avatars fall back to their initial when the image can't load.
document.addEventListener('error', (e) => { if (e.target.matches?.('.avatar img')) e.target.remove(); }, true);

// Live filter boxes inside dialogs: <input data-filter="#container">, rows carry data-filter-text.
document.addEventListener('input', (e) => {
  const container = e.target.dataset?.filter && $(e.target.dataset.filter);
  if (!container) return;
  const query = e.target.value.trim().toLowerCase();
  $$('[data-filter-text]', container).forEach((row) => {
    row.hidden = !!query && !row.dataset.filterText.toLowerCase().includes(query);
  });
});

// New items: fill the ID from the name until the ID is edited by hand.
for (const [nameId, slugId] of [['targetName', 'targetId'], ['libraryName', 'libraryId']]) {
  const slug = document.getElementById(slugId);
  slug.addEventListener('input', () => { slug.dataset.touched = '1'; });
  document.getElementById(nameId).addEventListener('input', (e) => {
    if (!slug.disabled && !slug.dataset.touched) slug.value = slugify(e.target.value);
  });
  slug.closest('dialog').addEventListener('close', () => { delete slug.dataset.touched; });
}

// =============================================================================
// First-run setup + boot
// =============================================================================

onSubmit($('#setupForm'), async () => {
  await api('POST', '/api/setup/init', {
    repo_name: $('#setupRepoName').value.trim(),
    repo_description: $('#setupRepoDescription').value.trim(),
    repo_url: $('#setupRepoUrl').value.trim(),
    repo_icon: $('#setupRepoIcon').value.trim(),
  });
  await boot();
});

async function boot() {
  const status = await api('GET', '/api/setup/status');
  $('#setupScreen').hidden = status.initialized;
  $('#app').hidden = !status.initialized;
  if (!status.initialized) return;

  showPage();
  await loadSettings(); // first, so repo rows can compare against the default APK pattern
  await Promise.all([loadRepos(), loadChannel(CHANNELS.mobile), loadChannel(CHANNELS.tv)]);
  // Pick a "Check now" that's still running back up after a reload.
  const checkStatus = await api('GET', '/api/repos/check-status').catch(() => ({}));
  if (checkStatus.running) pollRepoCheck();
}

buildPatchPages();
reportErrors(boot)();
