// background.js - Telemetry Relay and Configuration Proxy
const LOCAL_SERVER_BASE = "http://localhost:8080";

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === "getConfig") {
        const targetDomain = request.domain;
        const configUrl = `${LOCAL_SERVER_BASE}/config?domain=${encodeURIComponent(targetDomain)}`;

        console.log(`[Background] Fetching target config for: ${targetDomain}`);

        fetch(configUrl)
            .then(response => {
                if (!response.ok) {
                    throw new Error(`Server returned HTTP ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                sendResponse({ success: true, config: data });
            })
            .catch(error => {
                console.error("[Background] Failed to retrieve domain configuration: ", error);
                sendResponse({ success: false, error: error.message });
            });

        return true; // Keeps the runtime message port open for asynchronous response
    }

    if (request.action === "submitData") {
        const submitUrl = `${LOCAL_SERVER_BASE}/submit-data`;
        const payload = request.payload;

        console.log(`[Background] Submitting crawled telemetry batch for: ${payload.domain}`);

        fetch(submitUrl, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload)
        })
            .then(response => {
                if (!response.ok) {
                    throw new Error(`Server rejected telemetry payload with status ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                sendResponse({ success: true, result: data });
            })
            .catch(error => {
                console.error("[Background] Telemetry upload failed: ", error);
                sendResponse({ success: false, error: error.message });
            });

        return true; // Keeps the runtime message port open for asynchronous response
    }
});