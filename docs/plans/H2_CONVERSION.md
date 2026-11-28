<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->

# H2 conversion: inline table, record and tabs panels on the Rachana Core fragments

Inline table, kv and tabs panels in the non-risk Sutras: 143; converted: 116 (115 before `sic-directory`, whose `where` is now a `core/data-table` parameter); left inline: 27.
(Risk packs move with R2. BI LiveTables move to the live engine in i10. The `hbar`, `ladder`, `metric`, `line`, `links`, `provenance`, `surface`, `candlestick`, `pivot`-only, `graph`, `scatter`, `map`, `status` and `area` panels have no Core fragment of this shape and are not part of this pass.)
The conversion is `tools/packgen/common/corefrag.py`, called from `sutradoc.enrich` (every generator) and run once over the hand-written Sutras; a table becomes `core/data-table@1`, a kv `core/record-card@1` (record `$`, a title: a plain record view), a tabs `core/tabbed-views@1`. The panel keeps its old id: the use passes it as `with: { id: <old id> }` (Rachana 1.1.1, `panel-id`) and also writes it as `as:`; ids, function keys, `maskedPanels`, events and `panel:` references are unchanged, and the equivalence tests compare the ids exactly.

| generator / file | panel | fragment | converted? | why not |
|---|---|---|---|---|
| config/packs/core/compute-demo/sutras/path-book.v1.sutra.yaml | stamp | core/record-card | no | no columns: key |
| config/packs/core/core/sutras/collateral-and-margin/clearing-account.v1.sutra.yaml | trades | core/data-table | no | keys with no fragment parameter: pivot |
| config/packs/core/core/sutras/counterparty-and-legal/ccp.v1.sutra.yaml | accounts | core/data-table | yes |  |
| config/packs/core/core/sutras/counterparty-and-legal/counterparty-group.v1.sutra.yaml | members | core/data-table | yes |  |
| config/packs/core/core/sutras/counterparty-and-legal/counterparty.v1.sutra.yaml | nettingSets | core/data-table | no | keys with no fragment parameter: phone |
| config/packs/core/core/sutras/counterparty-and-legal/counterparty.v1.sutra.yaml | kyc | core/record-card | no | keys with no fragment parameter: phone |
| config/packs/core/core/sutras/counterparty-and-legal/counterparty.v1.sutra.yaml | computed | core/record-card | no | keys with no fragment parameter: phone |
| config/packs/core/core/sutras/counterparty-and-legal/counterparty.v1.sutra.yaml | computedSets | core/data-table | no | keys with no fragment parameter: phone |
| config/packs/core/core/sutras/counterparty-and-legal/issuer.v1.sutra.yaml | bonds | core/data-table | yes |  |
| config/packs/core/core/sutras/market-data/calendar.v1.sutra.yaml | holidays | core/data-table | yes |  |
| config/packs/core/core/sutras/organisation/book.v1.sutra.yaml | trades | core/data-table | no | keys with no fragment parameter: pivot |
| config/packs/core/core/sutras/organisation/desk.v1.sutra.yaml | books | core/data-table | yes |  |
| config/packs/core/core/sutras/organisation/desk.v1.sutra.yaml | positions | core/data-table | no | keys with no fragment parameter: pivot |
| config/packs/core/core/sutras/organisation/legal-entity.v1.sutra.yaml | desks | core/data-table | yes |  |
| config/packs/core/core/sutras/organisation/trader.v1.sutra.yaml | books | core/data-table | yes |  |
| config/packs/core/gleif/sutras/lei-browse.v1.sutra.yaml | found | core/data-table | yes |  |
| config/packs/core/gleif/sutras/lei-browse.v1.sutra.yaml | groups | core/data-table | no | keys with no fragment parameter: emits, children |
| config/packs/core/gleif/sutras/lei-countries.v1.sutra.yaml | countries | core/data-table | yes |  |
| config/packs/core/gleif/sutras/lei-country.v1.sutra.yaml | groups | core/data-table | yes |  |
| config/packs/core/gleif/sutras/lei-country.v1.sutra.yaml | note | core/record-card | yes |  |
| config/packs/core/gleif/sutras/lei-entity.v1.sutra.yaml | parents | core/data-table | no | keys with no fragment parameter: children, expand, highlight |
| config/packs/core/gleif/sutras/lei-entity.v1.sutra.yaml | addresses | core/record-card | yes |  |
| config/packs/core/gleif/sutras/lei-entity.v1.sutra.yaml | identifiers | core/record-card | yes |  |
| config/packs/core/gleif/sutras/lei-entity.v1.sutra.yaml | listed | core/record-card | yes |  |
| config/packs/core/gleif/sutras/lei-entity.v1.sutra.yaml | exceptions | core/data-table | yes |  |
| config/packs/core/gleif/sutras/lei-group.v1.sutra.yaml | tree | core/data-table | no | keys with no fragment parameter: children, expand |
| config/packs/core/gleif/sutras/lei-group.v1.sutra.yaml | facts | core/record-card | yes |  |
| config/packs/core/gleif/sutras/lei-group.v1.sutra.yaml | seeds | core/data-table | yes |  |
| config/packs/core/gleif/sutras/lei-mapping.v1.sutra.yaml | mapping | core/record-card | yes |  |
| config/packs/core/gleif/sutras/lei-relationship.v1.sutra.yaml | relationship | core/record-card | yes |  |
| config/packs/core/gleif/sutras/lei-relationship.v1.sutra.yaml | periods | core/data-table | yes |  |
| examples/packs/finance/sutras/books/book-pnl.v1.sutra.yaml | trades | core/data-table | no | keys with no fragment parameter: pivot |
| examples/packs/finance/sutras/commodities/listed-future.v1.sutra.yaml | contract | core/record-card | no | see report |
| examples/packs/finance/sutras/commodities/listed-future.v1.sutra.yaml | margin | core/record-card | yes |  |
| examples/packs/finance/sutras/credit/netting-set.v1.sutra.yaml | trades | core/data-table | no | keys with no fragment parameter: moreLabel, pivot |
| examples/packs/finance/sutras/credit/netting-set.v1.sutra.yaml | csa | core/record-card | no | see report |
| examples/packs/finance/sutras/credit/netting-set.v1.sutra.yaml | collateral | core/record-card | no | see report |
| examples/packs/finance/sutras/fx/fx-swap.v2.sutra.yaml | legs | core/tabbed-views | yes |  |
| examples/packs/finance/sutras/fx/fx-swap.v2.sutra.yaml | cashflows | core/data-table | yes |  |
| examples/packs/finance/sutras/rates/irs-vanilla.v3.sutra.yaml | legs | core/tabbed-views | yes |  |
| examples/packs/finance/sutras/rates/irs-vanilla.v3.sutra.yaml | cashflows | core/data-table | yes |  |
| examples/packs/finance/sutras/rates/irs-vanilla.v3.sutra.yaml | leg2 | core/record-card | yes |  |
| examples/packs/genomics/sutras/genomics-and-biology/clinical-trial.v1.sutra.yaml | arms | core/data-table | yes |  |
| examples/packs/genomics/sutras/genomics-and-biology/expression-study.v1.sutra.yaml | results | core/data-table | yes |  |
| examples/packs/genomics/sutras/genomics-and-biology/gene.v1.sutra.yaml | variants | core/data-table | yes |  |
| examples/packs/genomics/sutras/genomics-and-biology/pathway.v1.sutra.yaml | members | core/data-table | yes |  |
| examples/packs/genomics/sutras/genomics-and-biology/protein.v1.sutra.yaml | domains | core/data-table | yes |  |
| examples/packs/genomics/sutras/genomics-and-biology/sample.v1.sutra.yaml | calls | core/data-table | yes |  |
| examples/packs/genomics/sutras/genomics-and-biology/sequencing-run.v1.sutra.yaml | samples | core/data-table | yes |  |
| examples/packs/genomics/sutras/genomics-and-biology/variant.v1.sutra.yaml | evidence | core/data-table | yes |  |
| examples/packs/logistics/sutras/shipment.v1.sutra.yaml | route | core/data-table | yes |  |
| examples/packs/logistics/sutras/shipment.v1.sutra.yaml | cargo | core/record-card | yes |  |
| examples/packs/retail-banking/sutras/retail-banking/card-account.v1.sutra.yaml | statements | core/data-table | yes |  |
| examples/packs/retail-banking/sutras/retail-banking/customer.v1.sutra.yaml | products | core/data-table | yes |  |
| examples/packs/retail-banking/sutras/retail-banking/mortgage.v1.sutra.yaml | payments | core/data-table | yes |  |
| examples/packs/retail-banking/sutras/retail-banking/retail-portfolio.v1.sutra.yaml | stages | core/data-table | yes |  |
| modules/banking/packs/trading/sutras/endur/endur-callnot.v1.sutra.yaml | deposit | core/record-card | yes |  |
| modules/banking/packs/trading/sutras/endur/endur-cash.v1.sutra.yaml | payment | core/record-card | yes |  |
| modules/banking/packs/trading/sutras/endur/endur-comfut.v1.sutra.yaml | contract | core/record-card | yes |  |
| modules/banking/packs/trading/sutras/endur/endur-commodity.v1.sutra.yaml | delivery | core/record-card | yes |  |
| modules/banking/packs/trading/sutras/endur/endur-commodity.v1.sutra.yaml | inventory | core/record-card | yes |  |
| modules/banking/packs/trading/sutras/endur/endur-comswap.v1.sutra.yaml | fixed-float | core/record-card | yes |  |
| modules/banking/packs/trading/sutras/endur/endur-comswap.v1.sutra.yaml | basis | core/record-card | yes |  |
| modules/banking/packs/trading/sutras/pages/book-desk.v1.sutra.yaml | counterparty | core/record-card | yes |  |
| modules/banking/packs/trading/sutras/pages/book-desk.v1.sutra.yaml | limit | core/record-card | yes |  |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | top-holdings | core/data-table | no | keys with no fragment parameter: moreLabel |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | the-fund | core/record-card | yes |  |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | flows | core/data-table | yes |  |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | providers | core/record-card | yes |  |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | views | core/tabbed-views | no | converted by hand (slot design) |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | concentration | core/record-card | no | see report |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | derivatives | core/data-table | no | see report |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | counterparties | core/data-table | no | see report |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | holdings | core/data-table | no | see report |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | also-held-by | core/data-table | no | see report |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | coverage | core/record-card | no | see report |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | weights | core/data-table | no | see report |
| modules/capital-markets/packs/funds/sutras/fund.v1.sutra.yaml | sector-tilt | core/data-table | no | see report |
| modules/capital-markets/packs/funds/sutras/index-funds.v1.sutra.yaml | trackers | core/data-table | yes |  |
| modules/capital-markets/packs/us-equities/sutras/filing-feed.v1.sutra.yaml | forms | core/record-card | yes |  |
| modules/capital-markets/packs/us-equities/sutras/filing.v1.sutra.yaml | items | core/data-table | yes |  |
| modules/capital-markets/packs/us-equities/sutras/financial-fact.v1.sutra.yaml | history | core/data-table | yes |  |
| modules/capital-markets/packs/us-equities/sutras/financial-fact.v1.sutra.yaml | about | core/record-card | yes |  |
| modules/capital-markets/packs/us-equities/sutras/index-membership.v1.sutra.yaml | tracked-by | core/data-table | yes |  |
| modules/capital-markets/packs/us-equities/sutras/price-history.v1.sutra.yaml | analytics | core/record-card | yes |  |
| modules/capital-markets/packs/us-equities/sutras/price-history.v1.sutra.yaml | quote | core/record-card | yes |  |
| modules/capital-markets/packs/us-equities/sutras/sic-directory.v1.sutra.yaml | codes | core/data-table | no | keys with no fragment parameter: where |
| modules/capital-markets/packs/us-equities/sutras/us-company.v1.sutra.yaml | held-by-funds | core/data-table | yes |  |
| modules/capital-markets/packs/us-equities/sutras/us-company.v1.sutra.yaml | fund-ownership | core/record-card | yes |  |
| modules/capital-markets/packs/us-equities/sutras/us-company.v1.sutra.yaml | indexes | core/data-table | yes |  |
| modules/economy/packs/central-banks/sutras/central-bank.v1.sutra.yaml | series | core/data-table | yes |  |
| modules/economy/packs/central-banks/sutras/central-bank.v1.sutra.yaml | bank | core/record-card | yes |  |
| modules/economy/packs/central-banks/sutras/rate-series.v1.sutra.yaml | about | core/record-card | yes |  |
| modules/economy/packs/gdelt-events/sutras/event-country.v1.sutra.yaml | stories | core/data-table | yes |  |
| modules/economy/packs/national-statistics/sutras/statistics-series.v1.sutra.yaml | about | core/record-card | yes |  |
| modules/economy/packs/news-and-markets/sutras/listed-company.v1.sutra.yaml | quote | core/record-card | yes |  |
| modules/economy/packs/news-and-markets/sutras/listed-company.v1.sutra.yaml | headlines | core/data-table | yes |  |
| modules/economy/packs/news-and-markets/sutras/news-topic.v1.sutra.yaml | headlines | core/data-table | yes |  |
| modules/economy/packs/news-and-markets/sutras/news-topic.v1.sutra.yaml | pipeline | core/data-table | yes |  |
| modules/economy/packs/news-and-markets/sutras/news-topic.v1.sutra.yaml | everything | core/data-table | yes |  |
| modules/economy/packs/oecd/sutras/oecd-country.v1.sutra.yaml | measures | core/data-table | yes |  |
| modules/economy/packs/oecd/sutras/oecd-series.v1.sutra.yaml | about | core/record-card | yes |  |
| modules/economy/packs/official-news/sutras/press-release.v1.sutra.yaml | release | core/record-card | yes |  |
| modules/economy/packs/official-news/sutras/release-source.v1.sutra.yaml | latest | core/data-table | yes |  |
| modules/economy/packs/official-news/sutras/release-source.v1.sutra.yaml | terms | core/record-card | yes |  |
| modules/economy/packs/world-development/sutras/development-profile.v1.sutra.yaml | indicators | core/data-table | yes |  |
| modules/economy/packs/world-development/sutras/development-series.v1.sutra.yaml | about | core/record-card | yes |  |
| modules/economy/packs/world-economy/sutras/economics/country-group.v1.sutra.yaml | members | core/data-table | yes |  |
| modules/economy/packs/world-economy/sutras/economics/economic-forecast.v1.sutra.yaml | projections | core/data-table | yes |  |
| modules/economy/packs/world-economy/sutras/politics-and-society/election.v1.sutra.yaml | results | core/data-table | yes |  |
| modules/economy/packs/world-economy/sutras/politics-and-society/jurisdiction.v1.sutra.yaml | legislature | core/data-table | yes |  |
| modules/economy/packs/world-economy/sutras/politics-and-society/jurisdiction.v1.sutra.yaml | indicators | core/data-table | yes |  |
| modules/economy/packs/world-economy/sutras/politics-and-society/jurisdiction.v1.sutra.yaml | partiesListed | core/data-table | yes |  |
| modules/genomics/packs/genomics-showcase/sutras/association.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/genomics-showcase/sutras/cnv-segment.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/genomics-showcase/sutras/pathway-gene.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/genomics-showcase/sutras/study-gene.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/genomics-showcase/sutras/study-sample.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/genomics-showcase/sutras/study-variant.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/genomics-showcase/sutras/variant-call.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/protein-engineering/sutras/design-mutation.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/protein-engineering/sutras/design-round.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/protein-engineering/sutras/design.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/protein-engineering/sutras/dms-score.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/protein-engineering/sutras/protein.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/protein-engineering/sutras/residue-contact.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/genomics/packs/protein-engineering/sutras/residue.v1.sutra.yaml | record | core/record-card | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/bond.v1.sutra.yaml | coupons | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/cap-vol-surface.v1.sutra.yaml | grid | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/commodity-curve.v1.sutra.yaml | points | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/commodity-vol-surface.v1.sutra.yaml | grid | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/correlation-matrix.v1.sutra.yaml | pairs | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/correlation-matrix.v1.sutra.yaml | factors | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/correlation-matrix.v1.sutra.yaml | stamp | core/record-card | no | no columns: key |
| modules/market-data/packs/market-data/sutras/market-data/credit-curve.v1.sutra.yaml | points | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/equity-beta.v1.sutra.yaml | regression | core/record-card | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/equity-beta.v1.sutra.yaml | window | core/record-card | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/equity-index.v1.sutra.yaml | constituents | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/equity-vol-surface.v1.sutra.yaml | grid | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/fx-forward-curve.v1.sutra.yaml | outright | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/fx-vol-surface.v1.sutra.yaml | grid | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/ir-vol-cube.v1.sutra.yaml | grid | core/data-table | yes |  |
| modules/market-data/packs/market-data/sutras/market-data/repo-curve.v1.sutra.yaml | points | core/data-table | yes |  |

## Left inline (Rachana 1.1.1 has no parameter for it)

- `core/data-table`: `pivot` (host profile feature), `moreLabel`, tree rows (`children`, `expand`, `highlight`; `core/tree-table` has no key/code/source), `emits`, `form`, `phone`.
- `core/record-card`: `facts` is still required, so a kv with `rows:` and no `columns:` (the record's own fields as they are) stays a `kv` panel (path-book `stamp`, correlation-matrix `stamp`, fund and finance flow-form kv panels); `phone` tiers.
- `core/tabbed-views`: the slot holds at most 6 panels; the fund `views` tab has 12 panels and a per-tab `when`, so it and the panels inside it stay inline. No bespoke workaround.
- Panels written in YAML flow form (`{ id: x, kind: kv, ... }`) are not converted by the script (listed-future `contract`, netting-set `csa` and `collateral`).
- A record-card also draws a `state` status panel (`<as>-state`) when the record has a `status` field: not seen in the samples.

## Proof state (2026-11-28)

Done on this lane (no build): generators' `--check` green for banking (183 Sutras), genomics showcase and protein engineering, liquidity,
climate, economics, oprisk, politics, publicsector, world, gleif, funds and us-equities (genomics and retail regenerated: one doc-text
capitalisation each); every changed YAML parses. Not yet run (the proof run): DocumentedSutrasTest, CoreSutraNextEquivalenceTest,
H2LakePanelEquivalenceTest, H2SamplePanelEquivalenceTest, Rachana conformance in Drishti, pack-Sutra parity, FundsPackTest, GleifPackTest.
`config/homes/*` already used the Core fragments before H2 and keep their `<as>-table` ids (no `id` passed).
