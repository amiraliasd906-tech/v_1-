package com.divarsmartsearch.app.presentation.screens.webview

/**
 * Ported directly from the old browser extension's content-list.js /
 * content-detail.js. Same passive-reading philosophy: only extracts
 * whatever is already rendered on the page the user is looking at
 * inside our own in-app browser tab — no extra network requests, no
 * simulated clicks on "show number" buttons.
 *
 * Re-injected periodically by DivarWebViewScreen (Divar's search page is
 * a single-page app, so a one-time injection at page load isn't enough
 * to catch content that appears later as the user scrolls/navigates).
 */
object JsExtractionScripts {

    /**
     * Simulates what a person scrolling the list would do: scrolls to the
     * bottom of the page (which triggers Divar's own infinite-scroll
     * loading) and, if a "نمایش موارد بیشتر" pagination button is present
     * instead of (or in addition to) infinite scroll, clicks it.
     * Re-run repeatedly by the caller (headless scanner or the on-screen
     * WebView) so the person never has to touch the screen themselves.
     *
     * Bug fix: this used to click ANY button whose text merely contained
     * "بیشتر" anywhere, e.g. "بیشتر بدانید" on an ad banner, or a
     * "فیلترهای بیشتر" control -- neither of which is the results
     * pagination button. Clicking one of those can navigate the WebView
     * (or open an overlay) away from the actual search-results list, and
     * because that happens deep inside a periodic background loop with no
     * one watching the screen, nothing ever brings it back: every later
     * scan cycle then runs against whatever page it landed on instead of
     * the real listings, which is exactly what "finds one ad and then
     * never finds any more" looks like from the outside. Only the exact
     * two-word pagination phrase (or a button whose ENTIRE text is just
     * "بیشتر", not merely containing it) is matched now.
     */
    val AUTO_SCROLL_SCRIPT = """
        (function () {
          try {
            var buttons = Array.prototype.slice.call(document.querySelectorAll('button'));
            for (var i = 0; i < buttons.length; i++) {
              var label = (buttons[i].innerText || '').trim();
              if (label.indexOf('موارد بیشتر') !== -1 || label === 'بیشتر') {
                buttons[i].click();
                break;
              }
            }
            window.scrollTo(0, document.body.scrollHeight);
            window.dispatchEvent(new Event('scroll'));
            window.dispatchEvent(new Event('resize'));

            // Bug fix: on a single-page app like Divar, the results list
            // is sometimes rendered inside its own internal scrollable
            // <div> (overflow-y: auto) rather than the page body itself.
            // In that case scrolling window/body does nothing to the
            // list, its infinite-scroll loading never fires, and no new
            // cards ever get a chance to render -- which looks exactly
            // like "only ever finds the first handful of ads". Scrolling
            // every element that actually has extra content to scroll
            // through covers that case too, at negligible cost since most
            // pages have very few genuinely scrollable elements.
            var candidates = document.querySelectorAll('div, main, section, ul');
            for (var c = 0; c < candidates.length; c++) {
              var el = candidates[c];
              if (el.scrollHeight - el.clientHeight > 40) {
                el.scrollTop = el.scrollHeight;
                el.dispatchEvent(new Event('scroll'));
              }
            }
          } catch (e) {
            // Best-effort only -- never let this break the page.
          }
        })();
    """.trimIndent()

    /** Runs on any divar.ir page; picks list-page or detail-page logic based on the URL. */
    val EXTRACTION_SCRIPT = """
        (function () {
          try {
            var PERSIAN_DIGITS = '۰۱۲۳۴۵۶۷۸۹';
            function toAsciiDigits(text) {
              return text.replace(/[۰-۹]/g, function(d) { return String(PERSIAN_DIGITS.indexOf(d)); });
            }
            function parseNumber(text) {
              if (!text) return null;
              var normalized = toAsciiDigits(text).replace(/[,٬٫]/g, '');
              var match = normalized.match(/\d+(\.\d+)?/);
              return match ? parseFloat(match[0]) : null;
            }
            function extractToken(href) {
              var match = href.match(/\/v\/[^\/]+\/([\w-]+)/);
              return match ? match[1] : null;
            }

            // Walks up from the listing's anchor to find the smallest
            // ancestor that still represents just THIS one card. Bug fix:
            // this used to walk up a fixed 4 levels unconditionally, which
            // on Divar's actual markup often lands on a shared grid/list
            // container wrapping MANY cards (or even page chrome). That
            // polluted every extracted listing's `description` (built from
            // this element's innerText) with the title/price/text of
            // neighboring listings and nearby UI, and — critically — that
            // shared text very often contains generic words like
            // "املاک"/"مشاور"/"کلید" somewhere on the page, so the hard
            // exclude keyword filters in FilterPipeline matched almost
            // every single extracted listing, no matter what it actually
            // said. Results: the results screen stayed empty. Stopping as
            // soon as one more step up would start covering a second
            // "/v/" anchor keeps the container scoped to exactly one card.
            function findCardContainer(anchor) {
              var candidate = anchor;
              var maxLevels = 8;
              for (var d = 0; d < maxLevels && candidate.parentElement; d++) {
                var parent = candidate.parentElement;
                var anchorsInParent = parent.querySelectorAll('a[href*="/v/"]').length;
                if (anchorsInParent > 1) break;
                candidate = parent;
              }
              return candidate;
            }

            function extractListPage() {
              var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href*="/v/"]'));
              var seen = {};
              var listings = [];
              for (var i = 0; i < anchors.length; i++) {
                var href = anchors[i].getAttribute('href');
                if (!href) continue;
                var token = extractToken(href);
                if (!token || seen[token]) continue;
                seen[token] = true;

                var card = findCardContainer(anchors[i]);

                var text = (card.innerText || '').trim();
                var lines = text.split('\n').map(function(l){return l.trim();}).filter(Boolean);
                if (lines.length === 0) continue;

                var title = lines[0];
                var priceLine = lines.filter(function(l){return l.indexOf('تومان') !== -1 || l.indexOf('توافقی') !== -1;})[0];
                var areaLine = lines.filter(function(l){return l.indexOf('متر') !== -1;})[0];

                listings.push({
                  divarToken: token,
                  url: new URL(href, location.origin).toString(),
                  title: title,
                  price: priceLine ? parseNumber(priceLine) : null,
                  area: areaLine ? parseNumber(areaLine) : null,
                  pricePerMeter: null,
                  neighborhood: null,
                  // The full description isn't available on the list page
                  // (only on the detail page), but the card's visible text
                  // is the only signal we have here. Using it as a stand-in
                  // lets the "مشاور"/"املاک" keyword filter catch agency
                  // listings immediately, instead of only after the user
                  // opens the listing and the real description is fetched.
                  description: text,
                  contactPhone: null
                });
              }
              return listings;
            }

            function extractDetailPage() {
              var match = location.pathname.match(/\/v\/[^\/]+\/([\w-]+)/);
              if (!match) return [];
              var token = match[1];

              var telLink = document.querySelector('a[href^="tel:"]');
              var phone = telLink ? telLink.getAttribute('href').replace('tel:', '').trim() : null;

              var paragraphs = Array.prototype.slice.call(document.querySelectorAll('p'));
              var description = null;
              if (paragraphs.length > 0) {
                var longest = paragraphs.reduce(function(a, b) {
                  return (b.innerText || '').length > (a.innerText || '').length ? b : a;
                });
                var text = (longest.innerText || '').trim();
                description = text.length > 20 ? text : null;
              }

              var bodyLines = (document.body.innerText || '').split('\n');
              var priceLine = bodyLines.filter(function(l){return l.indexOf('تومان') !== -1;})[0];

              var h1 = document.querySelector('h1');
              var title = h1 ? h1.innerText.trim() : document.title;

              return [{
                divarToken: token,
                url: location.href,
                title: title,
                description: description,
                price: priceLine ? parseNumber(priceLine) : null,
                area: null,
                pricePerMeter: null,
                neighborhood: null,
                contactPhone: phone
              }];
            }

            var listings = /^\/v\//.test(location.pathname) ? extractDetailPage() : extractListPage();

            // Bug fix: this used to only call the bridge when
            // listings.length > 0, so if the page's markup didn't match
            // any of our selectors (e.g. Divar changed their layout, or
            // the page hadn't finished loading yet), NOTHING was ever
            // sent to the Kotlin side -- not even a "found zero" signal.
            // That made a real extraction failure completely
            // indistinguishable from the script simply not having run
            // yet. Always calling the bridge (with an empty array when
            // nothing matched) lets the Kotlin side report an accurate,
            // honest count every cycle instead of staying silent.
            if (window.AndroidBridge) {
              window.AndroidBridge.onListingsExtracted(JSON.stringify(listings));
            }
          } catch (e) {
            // Swallow errors silently — extraction is best-effort and must
            // never break the page the user is actually trying to browse.
          }
        })();
    """.trimIndent()
}
