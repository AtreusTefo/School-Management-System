import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { AppComponent } from './app/app.component';
import { csrfInterceptor } from './app/csrf.interceptor';

/**
 * Bootstraps the standalone Angular app.
 *
 * provideHttpClient() makes HttpClient available everywhere so our
 * AssignmentService can make API calls.
 *
 * csrfInterceptor forwards the CSRF token to our API. Angular's built-in XSRF
 * support deliberately covers same-origin requests only, and in development the
 * API is on a different port - see the interceptor for the full explanation.
 */
bootstrapApplication(AppComponent, {
  providers: [
    provideHttpClient(withInterceptors([csrfInterceptor]))
  ]
}).catch((err) => console.error(err));
