# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""Your Home in a real browser, a real server behind (docs/architecture/ROLE_HOME_SUTRAS.md): ``/home`` is the one Home page. It checks what the page owns:
the Home is found and drawn by the Elements painter (every panel has its buttons), the picker appears only when more than one Home is offered (the stack's
person is an administrator, so several are), the choice is kept and the start Home follows it after a reload, "View as" is offered to the administrator,
a panel can be hidden and brought back, "Arrange my Home" is offered only where the Home allows it and toggles the arrange mode, no sideways scroll on a
phone, and a click event from a figure opens its mnemonic in the terminal. The figures themselves are the engines' and are tested there."""
import pytest

from test_designer_browser import page_in, stack  # noqa: F401 - the stack and pages
from test_workbench_browser import browser, wait  # noqa: F401


def drawn(pg):
    wait(pg, "document.querySelector('[data-role-home] .dv-view')")


def test_the_home_is_drawn_with_its_buttons(browser, stack):
    pg = page_in(browser, "dark", {"width": 1440, "height": 950})
    pg.goto(f"{stack}/home")
    drawn(pg)
    assert pg.locator("[data-role-home] .dv-btn").count() > 0, "each panel has its own buttons"
    assert pg.evaluate("document.querySelector('[data-role-home]').getAttribute('data-layout')") in ("calm", "cockpit")
    pg.close()


def test_the_picker_the_choice_view_as_hide_and_arrange(browser, stack):
    pg = page_in(browser, "dark", {"width": 1440, "height": 950})
    pg.goto(f"{stack}/home")
    drawn(pg)
    pick = pg.locator("[data-role-home-pick]")
    assert pick.count() == 1, "an administrator is offered more than one Home: the picker shows"
    with pg.expect_navigation():
        pick.select_option("trader")
    drawn(pg)
    assert pg.locator("[data-role-home]").get_attribute("data-role-home") == "trader", "the start Home follows the choice"
    assert pg.locator("[data-home-viewas]:not([hidden])").count() == 1, "an administrator may view as another Home"
    first = pg.locator("[data-role-home] .dv-panel").first
    count = pg.locator("[data-role-home] .dv-panel").count()
    first.locator("button:has-text('Hide')").first.click()
    wait(pg, f"document.querySelectorAll('[data-role-home] .dv-panel').length < {count}")
    assert pg.locator("[data-home-hidden]:not([hidden])").count() == 1, "a hidden panel can be brought back"
    pg.locator("[data-home-hidden] button").first.click()
    wait(pg, f"document.querySelectorAll('[data-role-home] .dv-panel').length >= {count}")
    arrange = pg.locator("[data-role-home-arrange]")
    assert arrange.count() == 1 and arrange.get_attribute("aria-pressed") == "false"
    arrange.click()
    assert arrange.get_attribute("aria-pressed") == "true" and pg.evaluate("document.querySelector('[data-role-home]').classList.contains('is-arranging')")
    arrange.click()
    assert arrange.get_attribute("aria-pressed") == "false"
    pg.close()


def test_a_home_on_a_phone_does_not_scroll_sideways_and_a_figure_click_opens_its_mnemonic(browser, stack):
    pg = page_in(browser, "light", {"width": 390, "height": 800})
    pg.goto(f"{stack}/home")
    drawn(pg)
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    pg.evaluate("document.querySelector('[data-role-home]').dispatchEvent(new CustomEvent('rachana:event', "
                "{bubbles: true, detail: {name: 'open0', payload: {command: 'VAR BANK'}}}))")
    pg.wait_for_url("**/go?q=VAR%20BANK*", timeout=5000)
    pg.close()
