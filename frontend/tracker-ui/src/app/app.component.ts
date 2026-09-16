import { Component, HostListener, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs/operators';
import { SessionService } from './session.service';
import { NotificationService } from './notification.service';
import { ConfirmService } from './confirm.service';
import { FieldErrorComponent } from './field-error.component';
import { HomeComponent } from './home.component';
import { ThemeService } from './theme.service';
import { NotificationFeedService } from './notification-feed.service';
import { MessagingService } from './messaging.service';
import { PasswordStrengthValidatorDirective } from './password-strength-validator.directive';
import { isPublicPath } from './app.routes';
import { PASSWORD_MIN_LENGTH, USERNAME_MAX_LENGTH } from './validation';

/**
 * THE SHELL
 * ---------
 * What used to be the whole application is now just the frame around it: the
 * app bar, the sign-in and forced-password-change screens that gate
 * everything else, the one shared error banner, and the navigation that picks
 * which of the four routed pages sits inside <router-outlet>.
 *
 * Nothing here loads courses, submissions or marks any more - that is what
 * splitting the app into pages MEANS. Each page component fetches exactly
 * what its own page needs, through the same AssignmentService as always, and
 * this shell only ever asks SessionService "who is signed in" and
 * NotificationService "is there an error to show".
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink, RouterLinkActive, RouterOutlet,
    FieldErrorComponent, HomeComponent, PasswordStrengthValidatorDirective
  ],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {

  readonly usernameMaxLength = USERNAME_MAX_LENGTH;
  readonly passwordMinLength = PASSWORD_MIN_LENGTH;

  /**
   * Whether the URL currently showing is one of the public pages - the about
   * page and the two legal pages, which anyone may read without an account.
   *
   * Kept as a plain field updated on NavigationEnd, and SEEDED SYNCHRONOUSLY
   * from the current URL in the constructor. The seeding is the part that
   * matters: on a hard navigation straight to /privacy, the first change
   * detection runs before any router event has been emitted, and a field
   * that started out false would render the sign-in card for one frame
   * before correcting itself. This app has already paid for two routing bugs
   * caused by reading a value that had not arrived yet.
   */
  isPublicPage = false;

  /**
   * THE TWO COLLAPSIBLE MENUS, AND WHY THEY EXIST.
   *
   * The app bar had outgrown one row. Measured at the point this was written,
   * the links plus the brand plus the user actions needed 1478px for an admin,
   * 1585px for a teacher and 1303px even for a student - against a 1080px
   * container. `.nav` carried flex-wrap, so instead of overflowing it wrapped,
   * and every role saw its navigation spread over two or three ragged lines.
   *
   * Two things collapse, for different reasons:
   *
   *   mobileMenuOpen - below 1280px there is no width for a horizontal nav at
   *   all, so every link moves into a hamburger panel. Tablets get the same
   *   treatment as phones because the measurements say they need it, not
   *   because tablets are "small".
   *
   *   userMenuOpen - the user chip, Password and Sign out are ~400px of the
   *   bar and are used far less often than the nav links beside them. Behind
   *   the avatar they free exactly the room the links needed. This is the part
   *   that makes a single row possible on a laptop rather than only on a wide
   *   desktop.
   */
  mobileMenuOpen = false;
  userMenuOpen = false;

  toggleMobileMenu(): void {
    this.mobileMenuOpen = !this.mobileMenuOpen;
    if (this.mobileMenuOpen) {
      this.userMenuOpen = false;   // never two panels open at once
    }
  }

  toggleUserMenu(): void {
    this.userMenuOpen = !this.userMenuOpen;
    if (this.userMenuOpen) {
      this.mobileMenuOpen = false;
    }
  }

  closeMenus(): void {
    this.mobileMenuOpen = false;
    this.userMenuOpen = false;
  }

  /**
   * Escape closes whichever is open.
   *
   * Expected of anything that overlays the page, and the only way out for
   * somebody using a keyboard who opened a menu by accident.
   */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closeMenus();
  }

  /**
   * A click anywhere else closes them.
   *
   * The menus themselves stop propagation in the template, so this only fires
   * for clicks genuinely outside. Without it a menu opened by accident can
   * only be closed by finding its trigger again, which on a phone means
   * hunting for a button now hidden behind the panel.
   */
  @HostListener('document:click')
  onDocumentClick(): void {
    this.closeMenus();
  }

  // Sign-in form
  loginUsername = '';
  loginPassword = '';
  signingIn = false;
  loginError: string | null = null;

  // Change-password form
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  changingPassword = false;
  passwordNotice: string | null = null;
  showPasswordForm = false;

  constructor(
    public session: SessionService,
    public notifications: NotificationService,
    public confirmDialog: ConfirmService,
    public theme: ThemeService,
    public feed: NotificationFeedService,
    public messaging: MessagingService,
    private router: Router
  ) {
    this.isPublicPage = isPublicPath(this.router.url);

  }

  ngOnInit(): void {
    /*
     * The badges are refreshed HERE as well as on navigation, because a hard
     * reload while already signed in resolves init() AFTER the first
     * NavigationEnd has come and gone - so the navigation hook alone asks for
     * the counts while the session is still unknown, gets nothing, and never
     * asks again.
     */
    this.session.init().subscribe({
      next: () => this.refreshBadges(),
      error: () => { /* SessionService reports its own failure */ }
    });

    /*
     * Once a navigation has SETTLED, the router itself is the authority on
     * whether the page showing is public - so read the resolved route's data
     * rather than pattern-matching the URL a second time. This is what makes
     * the 404 work: '**' matches any address, so no list of paths could ever
     * recognise one, but the route it resolves to carries public: true like
     * any other public page.
     *
     * The synchronous seed in the constructor still matters and is not
     * redundant: it covers the frames BEFORE the first NavigationEnd, which is
     * exactly when a hard navigation straight to /privacy would otherwise
     * flash the sign-in card.
     */
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe(() => {
        this.isPublicPage = this.readPublicFlagFromRoute();
        // A panel left open would cover the page it just opened.
        this.closeMenus();
        this.refreshBadges();
      });
  }

  /**
   * Re-read the two unread counts after every settled navigation.
   *
   * ON NAVIGATION RATHER THAN ON A TIMER, deliberately. A poll would put a
   * request on the wire every few seconds for every signed-in browser, for a
   * number that only changes when somebody else acts - and this app has no
   * push channel to do better. Refreshing when the user moves means the badge
   * is right whenever they are actually looking at it, which is the only time
   * it matters, at no cost while they sit still.
   *
   * Skipped entirely while signed out or while a password change is pending:
   * both endpoints would answer 401, and an error banner reading "could not
   * count notifications" on the sign-in screen would be noise about something
   * the visitor cannot act on.
   *
   * The errors are swallowed rather than shown for the same reason - a badge
   * that fails to load is not worth interrupting anybody over, and the page
   * itself reports properly if its own request fails. This is the one place in
   * this app where an empty error callback is right, so it says why rather
   * than looking like the missing handler CLAUDE.md forbids.
   */
  private refreshBadges(): void {
    if (!this.session.user() || this.mustChangePassword) {
      this.feed.reset();
      this.messaging.reset();
      return;
    }
    this.feed.refreshUnread().subscribe({ error: () => { /* badge only */ } });
    this.messaging.refreshUnread().subscribe({ error: () => { /* badge only */ } });
  }

  /** The number on the bell. */
  get unreadNotifications(): number {
    return this.feed.unread();
  }

  /** The number on the envelope. */
  get unreadMessages(): number {
    return this.messaging.unread();
  }

  /** The deepest activated route's `public` flag - see ngOnInit. */
  private readPublicFlagFromRoute(): boolean {
    let node = this.router.routerState.snapshot.root;
    while (node.firstChild) {
      node = node.firstChild;
    }
    return node.data['public'] === true;
  }

  // ----- theme -----------------------------------------------------------------

  /**
   * Where the brand mark leads.
   *
   * Not simply '/' for everyone: an admin sent there lands on the
   * teacher/student dashboard, whose services branch STUDENT vs TEACHER and
   * have no ADMIN case, so it would render an empty student-shaped view. The
   * app bar's two separate nav lists exist for the same reason.
   */
  get homeLink(): string {
    return this.session.isAdmin ? '/admin' : '/';
  }

  get themeIsDark(): boolean {
    return this.theme.effective() === 'dark';
  }

  toggleTheme(): void {
    this.theme.toggle();
  }

  /**
   * Whether <router-outlet> exists at all on this render.
   *
   * ONE OUTLET, SHARED BY BOTH STATES. It used to live inside the signed-in
   * branch, which is why a signed-out visitor had nowhere for a page to
   * appear and saw only the sign-in card. Extending that to the public pages
   * had to be done by widening this ONE condition rather than adding a
   * second <router-outlet> to the signed-out branch: two outlets with the
   * same name are only safe while their conditions are provably exclusive,
   * and that is a property somebody has to keep true by hand forever.
   *
   * The signed-in half of the condition is deliberately unchanged - a
   * pending password still hides every routed page, because the server would
   * refuse each one of their requests anyway.
   */
  get showRoutedPage(): boolean {
    if (this.checkingSession) {
      return false;
    }
    return this.user ? !this.mustChangePassword : this.isPublicPage;
  }

  /** The landing page and its sign-in card: signed out, and not reading a public page. */
  get showLanding(): boolean {
    return !this.checkingSession && !this.user && !this.isPublicPage;
  }

  get user() {
    return this.session.user();
  }

  get checkingSession(): boolean {
    return this.session.checkingSession();
  }

  get isTeacher(): boolean {
    return this.session.isTeacher;
  }

  get mustChangePassword(): boolean {
    return this.session.mustChangePassword;
  }

  get passwordFormVisible(): boolean {
    return this.mustChangePassword || this.showPasswordForm;
  }

  get userInitial(): string {
    const user = this.user;
    return user ? user.username.charAt(0).toUpperCase() : '?';
  }

  // ----- authentication --------------------------------------------------------

  onLogin(): void {
    const username = this.loginUsername.trim();
    if (!username || !this.loginPassword) {
      return;
    }
    this.signingIn = true;
    this.session.login(username, this.loginPassword).subscribe({
      next: () => {
        this.signingIn = false;
        this.loginPassword = '';
        this.loginError = null;
        this.passwordNotice = null;
        /*
         * Explicitly, rather than relying on the navigation below to trigger
         * it. Signing in while already ON '/' navigates to '/' - the same URL -
         * and the Router emits no NavigationEnd for that, so the badges stayed
         * empty until the user happened to click something. Found in a browser;
         * no unit test would have shown it, because the gap is in the Router's
         * behaviour rather than in anything this component computes.
         */
        this.refreshBadges();
        // One shared form for every role - the server, not the form, decided
        // who just signed in. Where they land follows from that: an admin
        // has no use for the teacher/student dashboard, and vice versa.
        this.router.navigateByUrl(this.session.isAdmin ? '/admin' : '/');
      },
      // Handled separately from NotificationService: a 401 here means the
      // credentials were wrong, NOT that a session expired. Routing it
      // through the generic handler produced "Your session has ended, please
      // sign in again" on the login screen, which is both confusing and
      // untrue - and showError's 401 branch also calls session.logout() and
      // redirects, neither of which makes sense for a login attempt that
      // never succeeded in the first place.
      error: (err: unknown) => {
        this.signingIn = false;
        const status = (err as { status?: number })?.status;
        if (status === 401) {
          this.loginError = 'Invalid username or password.';
        } else {
          this.notifications.showError(err, 'Could not sign in');
        }
      }
    });
  }

  onLogout(): void {
    this.session.logout().subscribe({
      next: () => {
        this.notifications.clear();
        // Otherwise the next person to sign in on this browser briefly sees
        // the previous account's unread count.
        this.feed.reset();
        this.messaging.reset();
        this.showPasswordForm = false;
        this.passwordNotice = null;
        this.resetPasswordFields();
        this.router.navigateByUrl('/');
      },
      error: (err) => this.notifications.showError(err, 'Could not sign out')
    });
  }

  // ----- account self-service (EPIC-09) ----------------------------------------

  openPasswordForm(): void {
    this.showPasswordForm = true;
    this.passwordNotice = null;
    this.resetPasswordFields();
  }

  /** Only reachable when the change is voluntary; a forced one has no way out. */
  closePasswordForm(): void {
    this.showPasswordForm = false;
    this.resetPasswordFields();
  }

  private resetPasswordFields(): void {
    this.currentPassword = '';
    this.newPassword = '';
    this.confirmPassword = '';
  }

  get passwordsMatch(): boolean {
    return this.newPassword === this.confirmPassword;
  }

  get passwordsMatchError(): string | null {
    return this.confirmPassword.length > 0 && !this.passwordsMatch
      ? 'The two passwords do not match.'
      : null;
  }

  get canSubmitPassword(): boolean {
    return !this.changingPassword
        && this.currentPassword.length > 0
        && this.newPassword.length >= PASSWORD_MIN_LENGTH
        && this.passwordsMatch;
  }

  onChangePassword(): void {
    if (!this.canSubmitPassword) {
      return;
    }
    this.changingPassword = true;
    const wasForced = this.mustChangePassword;
    this.session.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: () => {
        this.changingPassword = false;
        this.showPasswordForm = false;
        this.resetPasswordFields();
        this.passwordNotice = 'Your password has been changed.';
        // Coming out of the forced state, nothing has ever loaded - send the
        // account to the dashboard now that it can actually use the app.
        if (wasForced) {
          this.router.navigateByUrl('/');
        }
      },
      error: (err) => {
        this.changingPassword = false;
        this.notifications.showError(err, 'Could not change your password');
      }
    });
  }
}
