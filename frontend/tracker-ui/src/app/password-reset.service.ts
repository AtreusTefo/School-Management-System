import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

/**
 * THE FORGOTTEN-PASSWORD ENDPOINTS.
 *
 * Both are reachable while signed out, which is the whole point - and both go
 * through the CSRF interceptor like every other write, because being
 * unauthenticated does not make a request safe to forge.
 */
@Injectable({ providedIn: 'root' })
export class PasswordResetService {

  private readonly api = environment.apiBaseUrl;
  private readonly opts = { withCredentials: true };

  constructor(private http: HttpClient) {}

  /**
   * Ask for a link.
   *
   * ALWAYS SUCCEEDS, whether or not the account exists - the server answers
   * identically on purpose so this endpoint cannot be used to discover which
   * IDs are real. The page must therefore show the same confirmation either
   * way, and must NOT try to be helpful by saying "no such account".
   */
  requestReset(identifier: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(
      `${this.api}/api/auth/forgot-password`, { identifier }, this.opts);
  }

  /** Spend a link. This one DOES report failure - see the component. */
  resetPassword(token: string, newPassword: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(
      `${this.api}/api/auth/reset-password`, { token, newPassword }, this.opts);
  }
}
