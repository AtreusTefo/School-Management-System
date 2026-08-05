import { booleanAttribute, Directive, Input } from '@angular/core';
import { AbstractControl, NG_VALIDATORS, ValidationErrors, Validator } from '@angular/forms';
import { checkDecimal, MARK_MAX_DECIMAL_DIGITS, MARK_MAX_INTEGER_DIGITS } from './validation';

/**
 * Validates a TEXT field as a decimal number shaped like the DECIMAL(6,2)
 * columns marks are stored in - live, on every keystroke, the same way
 * `required` or `minlength` would.
 *
 * WHY A DIRECTIVE RATHER THAN A REACTIVE-FORMS VALIDATOR FUNCTION
 * This application uses template-driven forms throughout (`[(ngModel)]`), not
 * ReactiveFormsModule - a deliberate choice made when the forms were first
 * built, and not one this change revisits. A `Validator` directive is how a
 * custom rule plugs into THAT system: implementing `NG_VALIDATORS` makes
 * Angular treat `appDecimal` exactly like its own built-in `required` and
 * `maxlength` attributes, so it composes with them rather than living beside
 * them as a separate mechanism.
 *
 * Usage:
 *   <input appDecimal [(ngModel)]="markScore" name="markScore">
 *   <input appDecimal appDecimalPositive [(ngModel)]="markMaxScore" name="markMaxScore">
 *
 * `appDecimalPositive` distinguishes a SCORE (>= 0 - a mark of zero is a real
 * result) from a MAXIMUM (> 0 - a maximum of zero would make every percentage
 * a division by zero, which is exactly what ck_assessment_max_positive refuses
 * on the same two fields at the database).
 */
@Directive({
  selector: '[appDecimal]',
  standalone: true,
  providers: [
    { provide: NG_VALIDATORS, useExisting: DecimalValidatorDirective, multi: true }
  ]
})
export class DecimalValidatorDirective implements Validator {

  /**
   * `transform: booleanAttribute` is required, not decoration. Without it, a
   * bare `<input appDecimalPositive>` - the same shape as `<input required>` -
   * binds the string `""` rather than `true`, and `Boolean('')` is `false`.
   * The directive would then silently validate every field as a SCORE,
   * accepting a zero maximum score and never firing on it.
   */
  @Input({ transform: booleanAttribute }) appDecimalPositive = false;

  validate(control: AbstractControl): ValidationErrors | null {
    const raw = control.value;
    if (raw === null || raw === undefined || raw === '') {
      // Presence is `required`'s job, not this directive's - see checkDecimal.
      return null;
    }

    const result = checkDecimal(String(raw), {
      maxIntegerDigits: MARK_MAX_INTEGER_DIGITS,
      maxDecimalDigits: MARK_MAX_DECIMAL_DIGITS,
      requirePositive: this.appDecimalPositive
    });

    return result.ok ? null : { decimal: { reason: result.reason } };
  }
}
