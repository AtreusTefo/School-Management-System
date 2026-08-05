import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgModel } from '@angular/forms';
import { fieldErrorMessage } from './validation';

/**
 * THE ONE PLACE AN INLINE VALIDATION ERROR IS DRAWN
 * ---------------------------------------------------
 * Every validated field in this application - all nine forms of it - shows
 * its error through this component and nothing else:
 *
 *   <input #usernameModel="ngModel" required maxlength="50"
 *          [(ngModel)]="username" name="username">
 *   <app-field-error [control]="usernameModel" label="Username" />
 *
 * That is deliberately the ENTIRE contract. A field earns an inline error by
 * carrying a validator attribute (`required`, `minlength`, `maxlength`,
 * `appDecimal`) and a template reference variable; this component reads the
 * resulting `NgModel` state and asks validation.ts what sentence to show.
 * Nothing about displaying an error is decided per-field - there is one
 * implementation of "how do we tell the user this is wrong", used everywhere.
 *
 * WHY IT WAITS FOR touched OR dirty
 * An empty required field is technically invalid from the instant the form
 * renders. Showing that immediately would greet every user with a page of red
 * text before they have done anything - which trains people to ignore
 * validation messages instead of reading them. Waiting for the field to have
 * been typed in (`dirty`) or left (`touched`) means the error appears at the
 * moment it becomes actionable: either they just made it invalid by typing,
 * or they moved on with it still empty.
 *
 * Both are LIVE Angular states. Template-driven forms run change detection on
 * every keystroke, so this component's `message` getter re-evaluates on every
 * one - an error appears and clears as the user types, with no debounce and
 * no manual wiring.
 *
 * WHY externalError EXISTS SEPARATELY FROM control
 * Some rules are not about one field in isolation - "the two passwords must
 * match", "a score cannot exceed its maximum". Angular's per-control validator
 * model has nowhere natural to put a rule that spans two independent
 * `ngModel`s in a template-driven form. Rather than inventing a parallel
 * mechanism, those stay exactly what they already were: a plain getter on the
 * component, recalculated on every change-detection pass same as any other
 * template expression. externalError is how that getter's message reaches
 * this component, so a cross-field rule still renders through the identical
 * pipe as every single-field one - one look, whether the rule involves one
 * input or two.
 */
@Component({
  selector: 'app-field-error',
  standalone: true,
  imports: [CommonModule],
  template: `<span class="field__hint field__hint--warn" *ngIf="message" role="alert">{{ message }}</span>`
})
export class FieldErrorComponent {

  /** The ngModel this error belongs to. Pass via a template reference: `#x="ngModel"`. */
  @Input() control: NgModel | null = null;

  /** How the field is named in the generated sentence: "Username is required." */
  @Input() label = 'This field';

  /** A cross-field message from the host component, shown once the field has been touched. */
  @Input() externalError: string | null = null;

  get message(): string | null {
    const interacted = !!this.control && (this.control.touched || this.control.dirty);
    if (!interacted) {
      return null;
    }
    if (this.externalError) {
      return this.externalError;
    }
    if (!this.control || this.control.valid) {
      return null;
    }
    return fieldErrorMessage(this.control.errors, this.label);
  }
}
