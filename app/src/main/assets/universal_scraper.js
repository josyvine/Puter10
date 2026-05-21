/**
 * UNIVERSAL WEB CONTENT SCRAPER (Heuristic Reader Mode)
 * Focuses on unstructured general web layouts (Wikipedia, news sites, portals, blogs).
 * Utilizes text density and link ratio heuristics to isolate clean article body content
 * while ignoring peripheral page elements like sidebars, headers, comments, and ads.
 */

(function() {

    /**
     * CLEANING UTILITY
     * Collapses duplicate whitespace blocks, strips invisible marks, and normalizes linebreaks.
     */
    function cleanTextString(str) {
        if (!str) return "";
        return str
            .replace(/\u200e/g, '') // Remove Left-to-Right Marks
            .replace(/\n|\r/g, ' ') // Collapse newlines
            .replace(/\s\s+/g, ' ') // Collapse multiple spaces
            .trim();
    }

    /**
     * CORE HEURISTIC EXTRACTION ENGINE
     * Inspects DOM containers and scores ancestors based on paragraph and link character density.
     */
    function extractArticleParagraphs() {
        const url = window.location.href;
        const timestamp = new Date().toISOString();

        // 1. Isolate Page Title
        let rawTitle = document.title;
        const ogTitle = document.querySelector('meta[property="og:title"]');
        const twitterTitle = document.querySelector('meta[name="twitter:title"]');
        const h1Element = document.querySelector('h1');

        if (ogTitle && ogTitle.content) {
            rawTitle = ogTitle.content.trim();
        } else if (twitterTitle && twitterTitle.content) {
            rawTitle = twitterTitle.content.trim();
        } else if (h1Element && h1Element.innerText.trim()) {
            rawTitle = h1Element.innerText.trim();
        }

        const cleanTitle = cleanTextString(rawTitle);

        // 2. Clone Document Body to avoid altering current active screen presentation
        const bodyClone = document.body.cloneNode(true);

        // 3. Noise Filter: Strip known sidebar, menu, ad, and header wrappers
        const noiseSelectors = [
            'script', 'style', 'noscript', 'iframe', 'header', 'footer', 'nav', 'aside',
            '.sidebar', '#sidebar', '.menu', '#menu', '.footer', '#footer', '.header', '#header',
            '.nav', '#nav', '.ad', '.ads', '.advertising', '.social', '.sharing', '.comments',
            '.comment-list', '#comments', '.widget', '.widget-area', '.sidebar-container'
        ];
        noiseSelectors.forEach(selector => {
            bodyClone.querySelectorAll(selector).forEach(element => {
                element.remove();
            });
        });

        // 4. Traverser and Scorer Loop
        const candidateContainers = [];
        const paragraphElements = bodyClone.querySelectorAll('p');

        paragraphElements.forEach(p => {
            const parent = p.parentNode;
            if (!parent) return;

            const paragraphText = p.innerText.trim();
            // Discard single words, empty lines, and small helper tags
            if (paragraphText.length < 25) return;

            // Initialize score registry inside cloned DOM node attributes
            if (!parent.hasAttribute('data-heuristics-score')) {
                parent.setAttribute('data-heuristics-score', '0');
                candidateContainers.push(parent);
            }

            let score = parseFloat(parent.getAttribute('data-heuristics-score'));

            // Add score points scaled to text character count
            score += Math.min(paragraphText.length / 50, 10); 

            parent.setAttribute('data-heuristics-score', score.toString());
        });

        // 5. Select Winning Container using Link-Density Penalization
        let winningContainer = null;
        let highestScore = -1;

        candidateContainers.forEach(container => {
            let score = parseFloat(container.getAttribute('data-heuristics-score'));

            // Calculate total non-link text vs hyperlink markups
            const linkText = Array.from(container.querySelectorAll('a'))
                .map(a => a.innerText.trim())
                .join('');
            const totalText = container.innerText.trim();

            if (totalText.length > 0) {
                const linkDensity = linkText.length / totalText.length;
                if (linkDensity > 0.35) {
                    // Aggressively penalize high link ratios (menus, list templates, indexes)
                    score *= (1 - linkDensity);
                }
            }

            if (score > highestScore) {
                highestScore = score;
                winningContainer = container;
            }
        });

        // 6. Format Content into Paragraph Output
        let finalCleanText = "";

        if (winningContainer && highestScore > 4) {
            // Read child tags inside winning container (paragraphs and secondary headings)
            const contentNodes = Array.from(winningContainer.querySelectorAll('p, h2, h3'))
                .map(node => node.innerText.replace(/\s+/g, ' ').trim())
                .filter(text => text.length > 20);

            finalCleanText = contentNodes.join('\n\n');
        } else {
            // Universal Fallback: Gathers all low-link-density paragraphs from the body clone directly
            const fallbackParagraphs = Array.from(bodyClone.querySelectorAll('p'))
                .filter(p => {
                    const textVal = p.innerText.trim();
                    if (textVal.length < 35) return false;
                    const linksVal = Array.from(p.querySelectorAll('a')).map(a => a.innerText).join('');
                    return (linksVal.length / textVal.length) < 0.25;
                })
                .map(p => p.innerText.replace(/\s+/g, ' ').trim());

            finalCleanText = fallbackParagraphs.join('\n\n');
        }

        // 7. Structured JSON Assembly
        const scrapedId = "web_" + Date.now() + "_" + cleanTitle.replace(/[^a-zA-Z0-9]/g, "_").substring(0, 24);

        return {
            "type": "universal_web",
            "scraped_id": scrapedId,
            "metadata": {
                "url": url,
                "title": cleanTitle,
                "timestamp": timestamp
            },
            "content": finalCleanText.trim()
        };
    }

    /**
     * DUAL-ROUTE COMMUNICATION GATEWAY
     * Resolves payload delivery immediately based on runtime environment:
     * - Route 1: Dispatches straight to window.AndroidInterface native storage.
     * - Route 2: Performs window.parent.postMessage callback to browser.html as fallback.
     * ENHANCED: Includes try-catch wrapping and direct native logic exception logging.
     */
    function triggerScrapeCallback() {
        console.log("universal_scraper.js: Parsing core body content...");
        
        try {
            const payload = extractArticleParagraphs();

            // 1. Direct Web-to-Native Bridge Handoff
            if (window.AndroidInterface && window.AndroidInterface.addScrapedProduct) {
                window.AndroidInterface.addScrapedProduct(payload.scraped_id, JSON.stringify(payload));
            }

            // 2. Parent Container postMessage Handoff
            if (window.parent && window.parent !== window) {
                window.parent.postMessage({
                    type: "scraped_data_callback",
                    payload: payload
                }, "*");
            }
        } catch (error) {
            // FORENSIC SYSTEM: Intercept unhandled JS exceptions and pipe directly to native public logs
            console.error("universal_scraper.js: Parsing crashed!", error);

            if (window.AndroidInterface) {
                if (window.AndroidInterface.logHtmlGlitch) {
                    window.AndroidInterface.logHtmlGlitch("universal_scraper.js", "Exception caught during parsing:\n" + (error.stack || error.message));
                }
                if (window.AndroidInterface.reportGlitchedLogic) {
                    window.AndroidInterface.reportGlitchedLogic("SCRAP_FAILURE", "JavaScript extraction engine exception: " + error.message);
                }
            }

            // Also post message back to parent browser.html frame to immediately break the infinite orange hang
            if (window.parent && window.parent !== window) {
                window.parent.postMessage({
                    type: "scraped_data_error",
                    error: error.message
                }, "*");
            }
        }
    }

    // Register active postMessage event listeners for asynchronous triggers
    window.addEventListener("message", function(event) {
        if (event.data === "scrape" || (event.data && event.data.action === "scrape_universal")) {
            triggerScrapeCallback();
        }
    });

    // Auto-executes safety diagnostics on load
    console.log("universal_scraper.js initialized and listening on secure virtual frame.");
})(); 