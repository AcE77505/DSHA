if (window.top === window) {
  // Dedicated signed migration session uses its own native host; production delegates never attach to it.
  if (location.pathname === '/__dsha/browser-origin-restore' && document.body?.dataset.dshaOriginRestore === '1') {
    const migration = browser.runtime.connectNative('dsha-origin-restore');
    migration.onMessage.addListener(message => {
      if (message?.type !== 'origin-restore' || typeof message.configuration !== 'string') return;
      const host = document.head || document.documentElement, script = document.createElement('script');
      script.textContent = "window.dispatchEvent(new CustomEvent('dsha-origin-restore-start',{detail:" + JSON.stringify(message.configuration) + "}));";
      host.appendChild(script); script.remove();
    });
  }
  const port = window.__dshaStartupPort || browser.runtime.connectNative('dsha');
  port.onMessage.addListener(message => {
    if (message?.type !== 'back' || !Number.isSafeInteger(message.id)) return;
    let handled = false;
    try { handled = window.__dshaPageBack(); } catch {}
    port.postMessage({type:'back', id:message.id, handled});
  });
}
