import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { AppComponent } from './app/app.component';

/**
 * Bootstraps the standalone Angular app.
 * provideHttpClient() makes HttpClient available everywhere so our
 * AssignmentService can make API calls.
 */
bootstrapApplication(AppComponent, {
  providers: [provideHttpClient()]
}).catch((err) => console.error(err));
