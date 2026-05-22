// content.js - DOM Parser, Progressive Auto-Scroller, and Metadata Filter
(function () {
    const targetDomain = window.location.hostname;
    console.log(`[Crawler] Injecting crawler script on: ${targetDomain}`);

    // Request monitoring configuration from the background proxy worker
    chrome.runtime.sendMessage({ action: "getConfig", domain: targetDomain }, (response) => {
        if (!response || !response.success) {
            console.log(`[Crawler] No active monitoring profile registered for: ${targetDomain}`);
            return;
        }

        const config = response.config;
        const mode = config.mode; // "HISTORICAL" or "LIVE"
        const startTime = config.start_time;
        const endTime = config.end_time;

        console.log(`[Crawler] Active schedule found. Mode: ${mode}, Start: ${new Date(startTime).toLocaleString()}, End: ${new Date(endTime).toLocaleString()}`);

        executeCrawlerPipeline(mode, startTime, endTime);
    });

    async function executeCrawlerPipeline(mode, startTime, endTime) {
        // Step 1: Execute progressive scrolling based on operational mode
        if ("HISTORICAL".equalsIgnoreCase(mode)) {
            console.log("[Crawler] Executing aggressive historical scrolling...");
            await runProgressiveScroll(15, 1500); // 15 scroll cycles, 1.5 seconds delay each
        } else {
            console.log("[Crawler] Executing lightweight live polling scroll...");
            await runProgressiveScroll(2, 1000); // 2 scroll cycles, 1 second delay each
        }

        // Step 2: Extract and parse metadata to find the page/content publication date
        const pagePublishedTime = getPagePublishedDate();
        console.log(`[Crawler] Extracted content publication timestamp: ${new Date(pagePublishedTime).toLocaleString()}`);

        // Step 3: Parse DOM anchor tags
        const anchorTags = Array.from(document.querySelectorAll("a"));
        const extractedBatch = [];
        const seenUrls = new Set();

        anchorTags.forEach(anchor => {
            try {
                let href = anchor.href;
                if (!href) return;

                // Strip trailing hashes, query variables, and resolve relative paths
                const cleanUrl = cleanTargetUrl(href);

                // Boundary Constraint: Verify link belongs to target domain and is a web page
                if (isValidTarget(cleanUrl, targetDomain)) {
                    if (!seenUrls.has(cleanUrl)) {
                        seenUrls.add(cleanUrl);

                        // Capture link title (prioritizes inner text, falls back to html title attribute)
                        let title = anchor.innerText.trim() 
                            || anchor.title.trim() 
                            || document.title.trim() 
                            || "Untitled Article Link";

                        extractedBatch.push({
                            url: cleanUrl,
                            title: title
                        });
                    }
                }
            } catch (e) {}
        });

        // Step 4: Time Range Constraint Filtering
        // Verify if the scraped URLs fall within our schedule window bounds
        if (pagePublishedTime < startTime || pagePublishedTime > endTime) {
            console.log(`[Crawler] Scraping aborted. Page date (${new Date(pagePublishedTime).toLocaleString()}) lies outside configured schedule boundaries.`);
            return;
        }

        if (extractedBatch.length === 0) {
            console.log("[Crawler] Scan complete. No new target links discovered in the DOM.");
            return;
        }

        // Step 5: Submit deduplicated payload back to the background worker proxy
        const payload = {
            domain: targetDomain,
            urls: extractedBatch,
            publishedAt: pagePublishedTime
        };

        chrome.runtime.sendMessage({ action: "submitData", payload: payload }, (status) => {
            if (status && status.success) {
                console.log(`[Crawler] Scrape complete. Successfully synchronized ${status.result.new_links_saved} new links to database.`);
            } else {
                console.error("[Crawler] Telemetry submission failed: ", status ? status.error : "Unknown connection error.");
            }
        });
    }

    // Controlled scroll loop that checks if scroll height has stopped changing (reaches true bottom)
    async function runProgressiveScroll(maxScrolls, delayMs) {
        let lastScrollHeight = document.body.scrollHeight;
        for (let i = 0; i < maxScrolls; i++) {
            window.scrollTo(0, document.body.scrollHeight);
            await new Promise(resolve => setTimeout(resolve, delayMs));
            
            let currentScrollHeight = document.body.scrollHeight;
            if (currentScrollHeight === lastScrollHeight) {
                break; // Exit early if we reached the true bottom of the layout
            }
            lastScrollHeight = currentScrollHeight;
        }
    }

    // Strategy-based metadata date extractor
    function getPagePublishedDate() {
        try {
            // Strategy 1: Parse schema metadata blocks (JSON-LD)
            const jsonLdScripts = document.querySelectorAll('script[type="application/ld+json"]');
            for (let script of jsonLdScripts) {
                try {
                    const json = JSON.parse(script.innerText);
                    const items = Array.isArray(json) ? json : [json];
                    for (let obj of items) {
                        if (obj.datePublished) return new Date(obj.datePublished).getTime();
                        if (obj.uploadDate) return new Date(obj.uploadDate).getTime();
                        if (obj.dateCreated) return new Date(obj.dateCreated).getTime();
                        
                        // Parse nested @graph entities
                        if (obj["@graph"] && Array.isArray(obj["@graph"])) {
                            for (let graphObj of obj["@graph"]) {
                                if (graphObj.datePublished) return new Date(graphObj.datePublished).getTime();
                                if (graphObj.dateModified) return new Date(graphObj.dateModified).getTime();
                            }
                        }
                    }
                } catch (e) {}
            }

            // Strategy 2: Check standard meta headers
            const selectors = [
                'meta[property="article:published_time"]',
                'meta[name="pubdate"]',
                'meta[name="publish-date"]',
                'meta[property="og:article:published_time"]',
                'meta[name="date"]',
                'meta[name="dcterms.created"]',
                'meta[name="cXenseParse:reponly-pubdate"]'
            ];
            for (let sel of selectors) {
                const element = document.querySelector(sel);
                if (element && element.content) {
                    const parsed = Date.parse(element.content);
                    if (!isNaN(parsed)) return parsed;
                }
            }

            // Strategy 3: Search within inline times/time elements
            const timeTag = document.querySelector("time");
            if (timeTag) {
                if (timeTag.dateTime) {
                    const parsed = Date.parse(timeTag.dateTime);
                    if (!isNaN(parsed)) return parsed;
                }
                const parsed = Date.parse(timeTag.innerText);
                if (!isNaN(parsed)) return parsed;
            }
        } catch (err) {
            console.error("[Crawler] Meta date extraction error: ", err);
        }
        return Date.now(); // Fallback: default to current time
    }

    function cleanTargetUrl(rawUrl) {
        try {
            const urlObj = new URL(rawUrl);
            // Remove hashes and standard tracker queries to isolate the canonical page link
            urlObj.hash = "";
            const cleanParams = ["utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid"];
            cleanParams.forEach(param => urlObj.searchParams.delete(param));
            return urlObj.toString();
        } catch (e) {
            return rawUrl;
        }
    }

    function isValidTarget(url, targetDomain) {
        try {
            const urlObj = new URL(url);
            // Discard assets, images, and utility routes
            const extension = urlObj.pathname.split(".").pop().toLowerCase();
            const blacklist = ["png", "jpg", "jpeg", "gif", "pdf", "css", "js", "xml", "json", "zip", "mp3", "mp4"];
            if (blacklist.includes(extension)) return false;

            // Restrict domain bound rules
            return urlObj.hostname === targetDomain || urlObj.hostname.endsWith("." + targetDomain);
        } catch (e) {
            return false;
        }
    }

    // String helper polyfill for compatibility on older browsers
    function equalsIgnoreCase(str1, str2) {
        if (!str1 || !str2) return false;
        return str1.toLowerCase() === str2.toLowerCase();
    }
    String.prototype.equalsIgnoreCase = function (anotherString) {
        return equalsIgnoreCase(this, anotherString);
    };
})();