import { cleanup } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { afterEach, vi } from "vitest";

import i18n from "../app/i18n";

window.localStorage.setItem("lang", "en-US");
await i18n.changeLanguage("en-US");

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.useRealTimers();
  window.localStorage.clear();
  window.localStorage.setItem("lang", "en-US");
});

class ResizeObserverMock {
  observe() {}

  unobserve() {}

  disconnect() {}
}

Object.defineProperty(window, "ResizeObserver", {
  configurable: true,
  writable: true,
  value: ResizeObserverMock,
});

Object.defineProperty(window, "matchMedia", {
  configurable: true,
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

Object.defineProperty(window, "requestAnimationFrame", {
  configurable: true,
  writable: true,
  value: (callback: FrameRequestCallback) => window.setTimeout(callback, 0),
});

Object.defineProperty(window, "cancelAnimationFrame", {
  configurable: true,
  writable: true,
  value: (handle: number) => window.clearTimeout(handle),
});

Object.defineProperty(Element.prototype, "scrollIntoView", {
  configurable: true,
  writable: true,
  value: vi.fn(),
});
