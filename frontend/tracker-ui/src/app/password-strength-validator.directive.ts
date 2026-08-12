import { Directive } from '@angular/core';
import { AbstractControl, NG_VALIDATORS, ValidationErrors, Validator } from '@angular/forms';
import { checkPasswordStrength } from './validation';

/**
 * Validates a password field for uppercase / digit / symbol content - live,
 * on every keystroke, the same way `required` or `minlength` would.
 *
 * A `Validator` directive for the same reason `DecimalValidatorDirective` is
 * one: this application uses template-driven forms throughout, and
 * implementing `NG_VALIDATORS` is what makes `appPasswordStrength` compose
 * with Angular's own built-in attributes rather than living beside them as a
 * separate mechanism.
 *
 * Length is deliberately NOT this directive's concern - pair it with
 * `[minlength]` the way every password field in this application already
 * does; see checkPasswordStrength's own comment for why combining the two
 * would make one validator lie about which rule actually failed.
 *
 * Usage:
 *   <input appPasswordStrength [(ngModel)]="temporaryPassword"
 *          name="temporaryPassword" required [minlength]="passwordMinLength">
 */
@Directive({
  selector: '[appPasswordStrength]',
  standalone: true,
  providers: [
    { provide: NG_VALIDATORS, useExisting: PasswordStrengthValidatorDirective, multi: true }
  ]
})
export class PasswordStrengthValidatorDirective implements Validator {

  validate(control: AbstractControl): ValidationErrors | null {
    const raw = control.value;
    if (raw === null || raw === undefined || raw === '') {
      // Presence is `required`'s job, not this directive's.
      return null;
    }

    const result = checkPasswordStrength(String(raw));
    return result.ok ? null : { passwordStrength: { reason: result.reason } };
  }
}
