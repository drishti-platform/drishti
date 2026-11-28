---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: trading-systems
title: Trading systems
domain: trading
summary: The trade kind and the six systems that feed it (Endur, Calypso, Murex, WSS, SwapOne, ICI), one pack each; the live trade blotter and the Trading landings' figures
kinds: [trade]
---
# Trading systems
The `trading-systems` module is the trading data family: the pack `trading` (the one `trade` kind, its four derived kinds, the live blotter and the Trading landings' server part) and one pack for each source system, `endur`, `calypso`, `murex`, `wss`, `swapone` and `ici`. A source pack owns no kind: it extends `trading` and brings its own connector templates, its map of native fields to the canonical ones, its inbox rule and its deal screens; its rows carry `SOURCE_SYSTEM`. The pack is the switch (Admin -> Artifacts), off by default; who sees which system's trades is a row rule on `SOURCE_SYSTEM` (the example role `trader-<system>` of each source pack).


The live trade blotter: a live table (`blotters/trade.live.yaml`) of Rachana's engine, served at `/api/v1/rdp/tables/trade`. The trades from the
durable store, ticking from the `trade` kind's `live:` binding, editable where the site's configuration and the person's rights allow it,
each change written back through the binding's write path (a version per trade, the outbox, four eyes where the site asks, audit).

## Data structures

| Kind | Bound as | Fields the blotter uses |
|---|---|---|
| `trade` | `read:` the durable store; `live:` a messaging connector (Kafka, RabbitMQ, ActiveMQ) | `TRADE_ID` (key), `TRADE_VERSION` (version), `TRADE_STATUS`, `PORTFOLIO`, `TRADER_ID`, `COUNTERPARTY`, `PRODUCT_TYPE`, `BUY_SELL_CODE`, `QUANTITY`, `PRICE`, `CURRENCY`, `TRADE_DATE`, `MATURITY_DATE`, `SOURCE_UPDATE_TIME` |

The guide and tutorial: the bank data guide (`docs/guides/BANK_DATA_GUIDE.md`, in the package `guides/BANK_DATA_GUIDE.md`; Help, this module's section).
