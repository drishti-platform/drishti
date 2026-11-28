/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
(function (g) {
  'use strict';
  /* Your Home's behaviour; nothing is drawn here (docs/architecture/ROLE_HOME_SUTRAS.md). The page holds the person's Home as a ViewModel (the
     server bound the Home's Sutra over the home tables as the person and applied the person's own state); the Drishti Elements painter
     (drishti-views) draws it, buttons and all. What a button does comes back here as a host effect: pin, hide, move, snooze and reset are the
     person's own state on the server (POST /api/home/state answers the Home again, the server having checked the action); Explain and Open run
     in place. The picker keeps the start Home, "View as" (an administrator's, offered by the server) another Home for a while, "Arrange my
     Home" switches the grid's arrange mode (drishti_screen-grid.js; the arrangement is /me/arrange/<sutra>), and a figure's click event (the
     Rachana events open0..open7 of core/kpi-strip, open of the lists) runs its command in the terminal. */
  function drishti_url(p) { var f = globalThis.drishti_url; return typeof f === 'function' && f !== drishti_url ? f(p) : p; }   // drishti-core's base helper when the page has it; a page without it (the renderer, an embed) keeps the URL as it is
  var root = document.querySelector('[data-role-home]');
  if (!root) { return; }
  var D = (g.Drishti = g.Drishti || {});
  var host = root.querySelector('[data-role-home-view]');
  var data = JSON.parse(document.getElementById('role-home-data').getAttribute('data-home'));
  var handle = null;

  function csrf() {
    var m = document.querySelector('meta[name="csrf-token"]');
    return m ? m.getAttribute('content') : '';
  }

  function href(link) {
    var id = String((link && link.id) || '');
    if (link && link.kind === 'command') { return drishti_url('/go?q=' + encodeURIComponent(id + ' <GO>')); }
    if (link && link.kind === 'href') { return /^\/(?!\/)/.test(id) ? drishti_url(id) : null; }
    return link && link.kind && id ? drishti_url('/v/' + encodeURIComponent(link.kind) + '/' + encodeURIComponent(id)) : null;
  }

  function send(action, panel, keys) {
    return fetch(drishti_url('/api/home/state'), {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf() },
      body: JSON.stringify({ action: action, panel: panel || '', keys: keys || [] })
    }).then(function (r) { return r.ok ? r.json() : null; });
  }

  function again(next) {
    if (!next) { return; }
    data = next;
    root.setAttribute('data-role-home', next.home);
    root.querySelector('h1').textContent = next.title;
    buttons(root.querySelector('[data-home-viewas]'), root.querySelector('[data-home-viewas-group]'), 'View as', viewAsButtons(next), function (d) { send('viewas', d.value === OWN ? '' : d.value).then(again); });
    buttons(root.querySelector('[data-home-hidden]'), root.querySelector('[data-home-hidden-group]'), 'Hidden', hiddenButtons(next), function (d) { send('show', d.value).then(again); }, true);
    paint(next.view);
  }

  /* One row of Drishti Elements buttons (a button group); an empty list hides the row. */
  var groups = {};
  function buttons(row, box, label, list, onPick, plain) {
    row.hidden = list.length === 0;
    if (groups[label] && groups[label].destroy) { groups[label].destroy(); groups[label] = null; }
    box.textContent = '';
    if (!list.length) { return Promise.resolve(); }
    return D.elements.load('drishti-inputs').then(function () {
      var o = { label: label, mode: plain ? 'none' : 'single', buttons: list.map(function (b) { return { label: b.label, data: { value: b.value }, attrs: { title: b.title || '' } }; }) };
      if (!plain) { o.value = list.filter(function (b) { return b.pressed; }).map(function (b) { return b.value; })[0] || list[0].value; }
      var group = new D.elements.inputs.ButtonGroup(box, o);
      group.on(plain ? 'click' : 'change', function (d) { if (d && d.value) { onPick(d); } });
      groups[label] = group;
    });
  }

  /* "View as" is an administrator's: the server says so (viewAs.allowed) and refuses anyone else. The first button is the administrator's own Home. */
  var OWN = '*';
  function viewAsButtons(d) {
    var v = d.viewAs || {};
    if (!v.allowed || (d.homes || []).length < 2) { return []; }
    return [{ label: 'My Home', value: OWN, pressed: !v.value, title: 'Your own start Home' }].concat(d.homes.map(function (h) {
      return { label: h.title, value: h.home, pressed: v.value === h.home, title: 'Show the Home of ' + h.title };
    }));
  }
  function hiddenButtons(d) {
    return (d.hidden || []).map(function (h) { return { label: 'Show ' + h.title, value: h.id, title: 'Bring ' + h.title + ' back' }; });
  }

  function paint(view) {
    if (handle) { handle.dispose(); handle = null; }
    host.textContent = '';
    return D.elements.load('drishti-views').then(function () {
      handle = D.elements.views.paint(host, view, {
        shadow: false, header: 'full', density: view.layout && view.layout.density === 'compact' ? 'compact' : 'comfortable',
        links: { href: href },
        host: { run: run }
      });
      handle.on('link', function (e) {
        var to = href(e.detail && e.detail.link);
        if (to) { g.location.href = to; }
      });
      root.dispatchEvent(new CustomEvent('drishti:home-drawn', { bubbles: true, detail: { panels: data.panels } }));
    });
  }

  /* a button of a panel: { do: 'host', action, with: { panel, ... } } */
  function run(effect) {
    var w = effect.with || {}, a = effect.action;
    if (a === 'explain') {
      if (D.Explain && D.Explain.open) { D.Explain.open(w.request || {}, 'formula', host); }
    } else if (a === 'open') {
      if (typeof w.command === 'string' && w.command.trim()) { g.location.href = drishti_url('/go?q=' + encodeURIComponent(w.command.trim())); }
    } else {
      send(a, w.panel, w.keys).then(again);
    }
  }

  var pick = root.querySelector('[data-role-home-pick]');
  if (pick) {
    pick.addEventListener('change', function () {
      fetch(drishti_url('/api/home/me'), {
        method: 'PUT', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf() },
        body: JSON.stringify({ home: pick.value })
      }).then(function (r) { if (r.ok) { g.location.href = drishti_url('/home'); } });
    });
  }

  var arrange = root.querySelector('[data-role-home-arrange]');
  if (arrange) {
    arrange.addEventListener('click', function () {
      var on = arrange.getAttribute('aria-pressed') !== 'true';
      arrange.setAttribute('aria-pressed', on ? 'true' : 'false');
      root.classList.toggle('is-arranging', on);
      root.dispatchEvent(new CustomEvent('drishti:arrange', { bubbles: true, detail: { on: on } }));
    });
  }

  /* An event the renderer raises from a panel: { name, payload: { command } }. Only commands the Home's own tables carry run. */
  root.addEventListener('rachana:event', function (e) {
    var d = e.detail || {};
    var cmd = d.payload && d.payload.command;
    if (/^(open[0-7]?|open)$/.test(d.name || '') && typeof cmd === 'string' && cmd.trim()) {
      g.location.href = drishti_url('/go?q=') + encodeURIComponent(cmd.trim());
    }
  });

  buttons(root.querySelector('[data-home-viewas]'), root.querySelector('[data-home-viewas-group]'), 'View as', viewAsButtons(data), function (d) { send('viewas', d.value === OWN ? '' : d.value).then(again); });
  buttons(root.querySelector('[data-home-hidden]'), root.querySelector('[data-home-hidden-group]'), 'Hidden', hiddenButtons(data), function (d) { send('show', d.value).then(again); }, true);
  paint(data.view).then(function () { send('visited'); });       /* one visit per half hour is kept: "Since you were here" */
  D.roleHome = { root: root, paint: paint };
})(typeof window !== 'undefined' ? window : globalThis);
