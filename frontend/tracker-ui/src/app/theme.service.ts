import { Injectable, signal } from '@angular/core';

/** What the reader has actually asked for. 'system' means "follow the OS". */
export type ThemePreference = 'light' | 'dark' | 'system';

/** What is actually on screen once 'system' has been resolved. */
export type EffectiveTheme = 'light' | 'dark';

const STORAGE_KEY = 'tracker.theme';

/**
 * WHO DECIDES WHICH PALETTE IS ON SCREEN.
 *
 * The stylesheet already had a complete dark palette, applied by
 * @media (prefers-color-scheme: dark). That is a good default and it stays -
 * what it could not do is let somebody OVERRIDE it, which is the whole point of
 * a toggle. styles.css now reads:
 *
 *   :root                                   light tokens
 *   @media (dark) :root:not([data-theme="light"])   dark tokens
 *   :root[data-theme="dark"]                dark tokens
 *
 * so the OS still decides until this service writes data-theme onto <html>,
 * and after that the explicit choice wins in BOTH directions - light on a
 * dark-set machine as well as dark on a light-set one. A naive
 * `[data-theme="dark"]` alone would have handled only one of those.
 *
 * THREE STATES STORED, TWO OFFERED BY THE BUTTON.
 * The preference can be 'system', and that is what a first-time visitor gets -
 * silently adopting whatever their machine already asked for is better than
 * guessing. The button itself is a plain two-way toggle, because that is what
 * a toggle means to the person clicking it; choosing either value moves the
 * preference off 'system' deliberately.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {

  /** The stored choice, including 'system'. */
  readonly preference = signal<ThemePreference>('system');

  /** The palette actually rendering, with 'system' already resolved. */
  readonly effective = signal<EffectiveTheme>('light');

  private readonly darkQuery: MediaQueryList | null =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-color-scheme: dark)')
      : null;

  constructor() {
    this.preference.set(this.readStoredPreference());
    this.apply();

    /*
     * While the preference is 'system', the OS flipping (a scheduled
     * night mode, say) has to be followed live. Once somebody has chosen
     * explicitly, their choice outranks the OS and this does nothing -
     * apply() re-reads the preference rather than assuming.
     */
    this.darkQuery?.addEventListener('change', () => {
      if (this.preference() === 'system') {
        this.apply();
      }
    });
  }

  /**
   * Flip to the opposite of what is currently ON SCREEN.
   *
   * Deliberately keyed off the EFFECTIVE theme, not the preference. Starting
   * from 'system' resolved to dark, a toggle that reasoned about the
   * preference would have to pick a direction arbitrarily; keying off what
   * the reader can actually see means the first click always visibly changes
   * something, which is the only behaviour that is not confusing.
   */
  toggle(): void {
    this.set(this.effective() === 'dark' ? 'light' : 'dark');
  }

  set(preference: ThemePreference): void {
    this.preference.set(preference);
    this.writeStoredPreference(preference);
    this.apply();
  }

  /** Resolve the preference, write the attribute, and publish the result. */
  private apply(): void {
    const preference = this.preference();
    const effective: EffectiveTheme =
      preference === 'system' ? (this.darkQuery?.matches ? 'dark' : 'light') : preference;

    this.effective.set(effective);

    if (typeof document === 'undefined') {
      return;
    }
    const root = document.documentElement;
    if (preference === 'system') {
      // No attribute at all, so the media query is back in charge. Writing
      // data-theme="dark" here would look identical today and then stop
      // following the OS the moment it changed.
      root.removeAttribute('data-theme');
    } else {
      root.setAttribute('data-theme', preference);
    }
  }

  /*
   * localStorage ACCESS IS WRAPPED, BOTH WAYS.
   * Reading or writing it THROWS - not returns null - in a browser configured
   * to block site data, and in some embedded/preview contexts. An exception
   * escaping the constructor of a root-provided service would take the whole
   * application down over a colour preference, so both sides fail quietly and
   * fall back to following the OS.
   */
  private readStoredPreference(): ThemePreference {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system';
    } catch {
      return 'system';
    }
  }

  private writeStoredPreference(preference: ThemePreference): void {
    try {
      localStorage.setItem(STORAGE_KEY, preference);
    } catch {
      // The choice still applies for this page view; it just will not be
      // remembered. That is a strictly better outcome than an error.
    }
  }
}
