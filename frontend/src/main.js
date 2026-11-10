import Keycloak from "keycloak-js";
import "./style.css";

const API_BASE = "http://localhost:8081";
const APP_URL = "http://localhost:3000/";

const keycloak = new Keycloak({
  url: "http://localhost:8080",
  realm: "demo",
  clientId: "demo-web"
});

const elements = {
  status: document.querySelector("#status"),
  output: document.querySelector("#output"),
  login: document.querySelector("#login"),
  logout: document.querySelector("#logout"),
  identity: document.querySelector("#identity"),
  orders: document.querySelector("#orders"),
  create: document.querySelector("#create")
};

let initialized = false;
let busy = false;

function render() {
  const authenticated = Boolean(keycloak.authenticated);

  elements.login.hidden = authenticated;
  elements.login.disabled = !initialized || busy;

  elements.logout.hidden = !authenticated;
  elements.logout.disabled = busy;

  for (const button of [
    elements.identity,
    elements.orders,
    elements.create
  ]) {
    button.disabled = !authenticated || busy;
  }
}

function showSignedOut(message = "You are signed out.") {
  elements.status.textContent = message;
  render();
}

function showSignedIn() {
  const username =
    keycloak.tokenParsed?.preferred_username ?? "user";

  elements.status.textContent = `Signed in as ${username}.`;
  render();
}

function formatBody(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

async function callApi(path, method = "GET") {
  if (busy || !keycloak.authenticated) {
    return;
  }

  busy = true;
  render();

  try {
    // Refresh if the token expires within the next 30 seconds.
    try {
      await keycloak.updateToken(30);
    } catch {
      keycloak.clearToken();

      showSignedOut(
        "Your session could not be refreshed. Please sign in again."
      );

      elements.output.textContent =
        "No API request was sent.";

      return;
    }

    if (!keycloak.token) {
      showSignedOut("Please sign in again.");
      return;
    }

    const response = await fetch(`${API_BASE}${path}`, {
      method,
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${keycloak.token}`
      },

      // The API receives a bearer token, not browser cookies.
      credentials: "omit",

      // Do not follow API redirects with this request.
      redirect: "error",

      cache: "no-store",
      signal: AbortSignal.timeout(10000)
    });

    const text = await response.text();

    elements.output.textContent =
      `HTTP ${response.status}\n\n${formatBody(text)}`;

    if (response.status === 401) {
      keycloak.clearToken();

      showSignedOut(
        "The API rejected the token. Sign in again; if it repeats, check issuer and audience."
      );
    } else if (response.status === 403) {
      elements.status.textContent =
        "Signed in, but not permitted to perform this operation.";
    } else if (response.ok) {
      showSignedIn();
    }

    // Do not automatically retry writes.
    // A real create-order endpoint will need idempotency support.
  } catch (error) {
    elements.output.textContent =
      `Request failed: ${error.message}\n\n` +
      "Check API availability, browser Console, and CORS settings.";
  } finally {
    busy = false;
    render();
  }
}

elements.login.addEventListener("click", async () => {
  if (busy || !initialized) {
    return;
  }

  busy = true;
  render();

  try {
    await keycloak.login({
      redirectUri: APP_URL
    });
  } catch {
    elements.status.textContent = "Unable to start login.";
  } finally {
    busy = false;
    render();
  }
});

elements.logout.addEventListener("click", async () => {
  if (busy) {
    return;
  }

  busy = true;
  render();

  try {
    await keycloak.logout({
      redirectUri: APP_URL
    });
  } catch {
    elements.status.textContent =
      "Logout could not complete. Please try again.";
  } finally {
    busy = false;
    render();
  }
});

elements.identity.addEventListener(
  "click",
  () => callApi("/api/me")
);

elements.orders.addEventListener(
  "click",
  () => callApi("/api/orders")
);

elements.create.addEventListener(
  "click",
  () => callApi("/api/orders", "POST")
);

keycloak.onAuthLogout = () => {
  showSignedOut();
};

async function start() {
  try {
    // Process a login callback if present.
    // Otherwise, show the signed-out page until Sign in is clicked.
    const authenticated = await keycloak.init({
      flow: "standard",
      pkceMethod: "S256",
      responseMode: "fragment",
      checkLoginIframe: false,
      redirectUri: APP_URL
    });

    initialized = true;

    if (authenticated) {
      showSignedIn();
    } else {
      showSignedOut();
    }
  } catch {
    elements.status.textContent =
      "Authentication initialization failed. Check Keycloak configuration.";

    elements.output.textContent =
      "Check the browser Console and Network tabs for details.";
  } finally {
    render();
  }
}

start();