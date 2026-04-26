// All imports pinned to the same @opentelemetry/api version (1.9.1) to avoid
// the singleton conflict that fires console errors when two api instances are
// present in the same page.
import { trace } from "https://esm.sh/@opentelemetry/api@1.9.1";
import { WebTracerProvider } from "https://esm.sh/@opentelemetry/sdk-trace-web@1.29.0?deps=@opentelemetry/api@1.9.1";
import { BatchSpanProcessor } from "https://esm.sh/@opentelemetry/sdk-trace-base@1.29.0?deps=@opentelemetry/api@1.9.1";
import { OTLPTraceExporter } from "https://esm.sh/@opentelemetry/exporter-trace-otlp-http@0.56.0?deps=@opentelemetry/api@1.9.1";
import { Resource } from "https://esm.sh/@opentelemetry/resources@1.29.0?deps=@opentelemetry/api@1.9.1";
import { ATTR_SERVICE_NAME } from "https://esm.sh/@opentelemetry/semantic-conventions@1.28.0";

// Route through nginx proxy on same origin to avoid CORS
const otlpEndpoint = `${location.protocol}//${location.hostname}:${location.port}/v1/traces`;

const provider = new WebTracerProvider({
  resource: new Resource({
    [ATTR_SERVICE_NAME]: "chat-app-frontend",
    "service.namespace": "chat-app",
    "deployment.environment": "local"
  })
});

provider.addSpanProcessor(new BatchSpanProcessor(new OTLPTraceExporter({
  url: otlpEndpoint
})));

provider.register();

const tracer = trace.getTracer("chat-app-frontend-ui");

const bootSpan = tracer.startSpan("frontend.boot");
bootSpan.setAttribute("browser.user_agent", navigator.userAgent);
bootSpan.end();

window.addEventListener("click", (event) => {
  const target = event.target;
  if (!(target instanceof Element)) {
    return;
  }
  const buttonLike = target.closest("button,a,[role='button']");
  if (!buttonLike) {
    return;
  }

  const span = tracer.startSpan("frontend.interaction.click");
  span.setAttribute("ui.element", buttonLike.tagName.toLowerCase());
  span.setAttribute("ui.label", buttonLike.textContent?.trim() || "unknown");
  span.end();
}, { passive: true });

window.addEventListener("beforeunload", () => {
  provider.forceFlush().catch(() => undefined);
});
