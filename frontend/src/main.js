import Keycloak from "keycloak-js";
import "./style.css";

// These are public application settings, not secrets.
const APP_URL = "http://localhost:3000/";
const API_BASE = "http://localhost:8081";

const keycloak = new Keycloak({
  url: "http://localhost:8080",
  realm: "demo",
  clientId: "demo-web"
});

const ui = {
  status: document.querySelector("#status"),
  output: document.querySelector("#output"),

  login: document.querySelector("#login"),
  logout: document.querySelector("#logout"),

  identity: document.querySelector("#identity"),
  orders: document.querySelector("#orders"),

  createForm: document.querySelector("#create-form"),
  createFields: document.querySelector("#create-fields"),
  product: document.querySelector("#product"),
  quantity: document.querySelector("#quantity"),
  requestKey: document.querySelector("#request-key"),
  newKey: document.querySelector("#new-key"),

  findForm: document.querySelector("#find-form"),
  findFields: document.querySelector("#find-fields"),
  orderId: document.querySelector("#order-id")
};

let initialized = false;
let busy = false;

function render() {
  const authenticated = Boolean(keycloak.authenticated);
  const canOperate = initialized && authenticated && !busy;

  ui.login.hidden = authenticated;
  ui.login.disabled = !initialized || busy;

  ui.logout.hidden = !authenticated;
  ui.logout.disabled = busy;

  ui.identity.disabled = !canOperate;
  ui.orders.disabled = !canOperate;

  ui.createFields.disabled = !canOperate;
  ui.findFields.disabled = !canOperate;
}

function setStatus(message) {
  ui.status.textContent = message;
}

function showSignedIn() {
  const username =
    keycloak.tokenParsed?.preferred_username ?? "user";

  setStatus(`Signed in as ${username}.`);
  render();
}

function showSignedOut(message = "You are signed out.") {
  setStatus(message);
  render();
}

function generateRequestKey() {
  ui.requestKey.value = crypto.randomUUID();
}

function formatBody(text) {
  if (!text) {
    return "(empty response)";
  }

  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

function showApiResponse(response, text) {
  const lines = [`HTTP ${response.status}`];

  const replayed =
    response.headers.get("Idempotency-Replayed");

  const location =
    response.headers.get("Location");

  if (replayed !== null) {
    lines.push(`Idempotency-Replayed: ${replayed}`);
  }

  if (location !== null) {
    lines.push(`Location: ${location}`);
  }

  lines.push("", formatBody(text));

  // Treat server responses as text, never executable HTML.
  ui.output.textContent = lines.join("\n");
}

async function callApi(
  path,
  {
    method = "GET",
    body,
    idempotencyKey
  } = {}
) {
  if (busy || !initialized || !keycloak.authenticated) {
    return;
  }

  busy = true;
  render();
  setStatus("Sending request…");

  try {
    // Refresh before making the request when expiry is near.
    try {
      await keycloak.updateToken(30);
    } catch {
      keycloak.clearToken();

      showSignedOut(
        "Session refresh failed. Please sign in again."
      );

      ui.output.textContent =
        "No API request was sent.";

      return;
    }

    if (!keycloak.token) {
      showSignedOut("Please sign in again.");
      ui.output.textContent = "No API request was sent.";
      return;
    }

    const headers = {
      Accept: "application/json",
      Authorization: `Bearer ${keycloak.token}`
    };

    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }

    if (idempotencyKey !== undefined) {
      headers["Idempotency-Key"] = idempotencyKey;
    }

    const response = await fetch(`${API_BASE}${path}`, {
      method,
      headers,

      body:
        body === undefined
          ? undefined
          : JSON.stringify(body),

      // Authenticate the API with the bearer header only.
      credentials: "omit",

      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(10000)
    });

    const text = await response.text();

    showApiResponse(response, text);

    if (response.status === 401) {
      keycloak.clearToken();

      showSignedOut(
        "The API rejected the token. Sign in again. " +
        "If this repeats, check issuer and audience settings."
      );

      return;
    }

    if (response.status === 403) {
      setStatus(
        "You are signed in but do not have permission for this operation."
      );

      return;
    }

    if (response.status === 404) {
      setStatus(
        "Order not found or not accessible to your account."
      );

      return;
    }

    if (response.status === 409) {
      setStatus(
        "This key was used with different order details. " +
        "Generate a new key for a new order."
      );

      return;
    }

    if (response.status === 400) {
      setStatus(
        "The request is invalid. Check the response for details."
      );

      return;
    }

    if (!response.ok) {
      setStatus(
        `The API returned an error: HTTP ${response.status}.`
      );

      return;
    }

    if (method === "POST") {
      let order;

      try {
        order = JSON.parse(text);
      } catch {
        setStatus(
          "Creation returned success, but its response was unreadable. " +
          "Keep the same key when retrying."
        );

        return;
      }

      if (typeof order.id === "string") {
        ui.orderId.value = order.id;
      }

      const replayed =
        response.headers.get("Idempotency-Replayed") === "true";

      setStatus(
        replayed
          ? "Existing order returned. No duplicate was created."
          : "Order created. Its ID is ready in the lookup field."
      );

      // Preserve the key so another identical submission is a replay.
      return;
    }

    showSignedIn();
  } catch (error) {
    const message =
      error instanceof Error ? error.message : "Unknown error";

    ui.output.textContent =
      `Request failed: ${message}\n\n` +
      "Check API availability, CORS, and the browser Console.\n\n" +
      (
        method === "POST"
          ? "Creation may have completed even though its response " +
            "was not received. Retry with the SAME key and details."
          : "You can retry this read request."
      );

    setStatus("Unable to complete the request.");

    // Do not retry automatically, especially for writes.
  } finally {
    busy = false;
    render();
  }
}

ui.login.addEventListener("click", async () => {
  if (!initialized || busy) {
    return;
  }

  busy = true;
  render();
  setStatus("Opening Keycloak login…");

  try {
    await keycloak.login({
      redirectUri: APP_URL
    });
  } catch {
    setStatus(
      "Unable to start login. Check Keycloak availability."
    );
  } finally {
    busy = false;
    render();
  }
});

ui.logout.addEventListener("click", async () => {
  if (busy || !keycloak.authenticated) {
    return;
  }

  busy = true;
  render();
  setStatus("Signing out…");

  try {
    await keycloak.logout({
      redirectUri: APP_URL
    });
  } catch {
    setStatus(
      "Logout could not complete. Please try again."
    );
  } finally {
    busy = false;
    render();
  }
});

ui.identity.addEventListener("click", () => {
  callApi("/api/me");
});

ui.orders.addEventListener("click", () => {
  callApi("/api/orders?limit=20&offset=0");
});

ui.newKey.addEventListener("click", () => {
  if (busy) {
    return;
  }

  generateRequestKey();

  setStatus(
    "New request key generated. The next successful submission " +
    "will create a new order."
  );
});

ui.createForm.addEventListener("submit", (event) => {
  event.preventDefault();

  if (busy || !keycloak.authenticated) {
    return;
  }

  ui.product.value = ui.product.value.trim();
  ui.requestKey.value = ui.requestKey.value.trim();

  if (!ui.createForm.reportValidity()) {
    return;
  }

  const quantity = Number(ui.quantity.value);

  if (
    !Number.isInteger(quantity) ||
    quantity < 1 ||
    quantity > 100
  ) {
    setStatus("Quantity must be an integer between 1 and 100.");
    return;
  }

  callApi("/api/orders", {
    method: "POST",
    idempotencyKey: ui.requestKey.value,
    body: {
      product: ui.product.value,
      quantity
    }
  });
});

ui.findForm.addEventListener("submit", (event) => {
  event.preventDefault();

  if (busy || !keycloak.authenticated) {
    return;
  }

  ui.orderId.value = ui.orderId.value.trim();

  if (!ui.findForm.reportValidity()) {
    return;
  }

  callApi(
    `/api/orders/${encodeURIComponent(ui.orderId.value)}`
  );
});

keycloak.onAuthLogout = () => {
  showSignedOut();
  ui.output.textContent = "You are signed out.";
};

async function start() {
  render();

  try {
    generateRequestKey();

    const authenticated = await keycloak.init({
      flow: "standard",
      pkceMethod: "S256",
      responseMode: "fragment",

      // No dependency on hidden-iframe session checks.
      checkLoginIframe: false,

      redirectUri: APP_URL
    });

    initialized = true;

    if (authenticated) {
      showSignedIn();
    } else {
      showSignedOut("You are signed out. Click Sign in to continue.");
    }
  } catch {
    setStatus(
      "Authentication initialization failed. " +
      "Check Keycloak settings, then reload."
    );

    ui.output.textContent =
      "Expected client: demo-web\n" +
      "Expected realm: demo\n" +
      `Expected frontend URL: ${APP_URL}\n\n` +
      "Check the browser Console and Network tabs for details.";
  } finally {
    render();
  }
}

start();