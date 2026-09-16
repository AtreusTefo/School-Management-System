import { ValidationErrors } from '@angular/forms';

/**
 * CENTRALIZED VALIDATION RULES
 * -----------------------------
 * Before this file, every form validated itself: a getter here checking
 * `.trim()`, a `[disabled]` expression there checking `.length >= 8`, each one
 * written out again at its own call site. Nothing stopped two forms from
 * stating the "same" rule slightly differently, and nothing here proved they
 * ever would.
 *
 * This file is the one place a rule is written down, and the one place its
 * wording is decided. Two things live here:
 *
 *   - constants and pure check functions that mirror a real backend
 *     constraint (a column length, a schema CHECK), so the two layers cannot
 *     drift apart from having been typed twice by hand;
 *   - fieldErrorMessage(), the single function that turns any Angular
 *     validation error object into a sentence a person reads. Every field in
 *     this application shows its inline error through FieldErrorComponent,
 *     which calls this and nothing else - so a rule worded once reads the
 *     same wherever it appears.
 *
 * WHY THESE ARE PLAIN FUNCTIONS, NOT A SERVICE
 * A validation rule is a pure question - "is this string too long?" - with no
 * dependency on anything Angular has to inject. Pure functions can be unit
 * tested with a single import and no TestBed, and a Directive or a component
 * getter can call them directly. Wrapping them in an injectable service would
 * add indirection with nothing to show for it.
 */

/** Every username column in this schema is app_user.username NVARCHAR(50). */
export const USERNAME_MAX_LENGTH = 50;

/** The shortest password this system accepts - mirrors AppUserService.MIN_PASSWORD_LENGTH. */
export const PASSWORD_MIN_LENGTH = 8;

/**
 * Every mark column is DECIMAL(6,2): six digits total, two of them after the
 * point, so the largest representable value is 9999.99. Mirrored here rather
 * than derived, because there is nowhere at the frontend boundary to derive it
 * FROM - the schema is the source of truth and this is a deliberate copy of it.
 */
export const MARK_MAX_INTEGER_DIGITS = 4;
export const MARK_MAX_DECIMAL_DIGITS = 2;

/**
 * The rest of the field-length bounds, one per backend @Size(max = ...). Named
 * here rather than typed as a bare number on each `maxlength` attribute, for
 * the same reason USERNAME_MAX_LENGTH is: a number sitting alone in a template
 * gives no hint that it is a copy of something, and the next person to touch
 * either side has nothing telling them the other one exists.
 */
export const ASSIGNMENT_TITLE_MAX_LENGTH = 200;       // assignment.title
export const ASSIGNMENT_DESCRIPTION_MAX_LENGTH = 2000; // assignment.description
export const SUBJECT_CODE_MAX_LENGTH = 20;            // subject.code
export const SUBJECT_NAME_MAX_LENGTH = 100;           // subject.name
export const CLASS_NAME_MAX_LENGTH = 50;              // school_class.name
export const ASSESSMENT_NAME_MAX_LENGTH = 100;        // assessment.name
export const MESSAGE_BODY_MAX_LENGTH = 2000;          // message.body

// person_profile - the admin panel's student/teacher onboarding forms.
export const PERSON_NAME_MIN_LENGTH = 2;              // first_name / last_name
export const PERSON_NAME_MAX_LENGTH = 50;             // first_name / middle_name / last_name
export const NATIONAL_ID_LENGTH = 9;                  // person_profile.national_id - an Omang
export const NATIONAL_ID_MAX_LENGTH = 9;              // kept as a name existing forms bind to
export const CONTACT_EMAIL_MAX_LENGTH = 100;          // person_profile.contact_email

/**
 * Unicode letters, spaces, hyphens and apostrophes - mirrors
 * PersonPatterns.NAME on the backend exactly, so a name refused here is
 * refused there for the identical reason. Not ASCII-only: this project
 * already treats names as carrying accented characters elsewhere (the
 * localeCompare sort in AssignmentTableComponent), and "O'Brien" or
 * "Van der Merwe" are real names, not edge cases a stricter pattern would
 * have to special-case.
 */
export const NAME_PATTERN = /^[\p{L} '-]+$/u;

/**
 * A Botswana Omang - mirrors PersonPatterns.NATIONAL_ID exactly.
 *
 * Nine digits, whose FIFTH is the gender marker: 1 for male, 2 for female.
 * Read as four digits, the marker, four more.
 *
 * That middle digit is the only self-describing position in the number, which
 * is what makes it worth checking at all: nine arbitrary digits have no
 * internal structure, so a transposition just produces another plausible
 * number. Constraining position 5 catches a mistake there at the form instead
 * of leaving a profile quietly attached to the wrong person.
 *
 * (An older convention marked gender with the seventh digit, 0-4 female and
 * 5-9 male. This is the modern rule, and matches ck_person_profile_omang at
 * the database - see V13.)
 */
export const NATIONAL_ID_PATTERN = /^[0-9]{4}[12][0-9]{4}$/;

/**
 * Male or female, read out of a valid Omang, or null when the number cannot
 * say. Mirrors Gender.fromOmang on the server.
 *
 * Used to show the derived gender beside the field as it is typed, which is
 * also the clearest way to explain WHY a number was refused - "the 5th digit
 * must be 1 or 2" means much more next to a field that has been showing
 * "Male" for every other entry.
 */
export function genderFromOmang(nationalId: string | null | undefined): 'Male' | 'Female' | null {
  if (!nationalId || !NATIONAL_ID_PATTERN.test(nationalId)) {
    return null;
  }
  return nationalId.charAt(4) === '1' ? 'Male' : 'Female';
}

/**
 * A Botswana mobile number stored as +267 followed by eight digits - mirrors
 * PersonPatterns.PHONE. The form only ever asks for the eight digits (see
 * PHONE_LOCAL_PATTERN); this is the shape of the value once the fixed prefix
 * is joined to it, used to validate what is actually sent to the server.
 */
export const PHONE_PATTERN = /^\+267[0-9]{8}$/;

/** What the phone INPUT itself accepts - the +267 prefix is fixed UI, not typed. */
export const PHONE_LOCAL_PATTERN = /^[0-9]{8}$/;
export const PHONE_COUNTRY_CODE = '+267';

export type DecimalRejectReason =
  | 'format' | 'tooManyDigits' | 'tooManyDecimals' | 'negative' | 'notPositive';

export interface DecimalCheckResult {
  ok: boolean;
  reason?: DecimalRejectReason;
}

/**
 * Check a typed decimal STRING against the same shape a DECIMAL(6,2) column
 * accepts - not against what a JavaScript `number` happens to allow.
 *
 * WHY A STRING, NOT A NUMBER
 * A mark stays text end to end in this application, deliberately: routing it
 * through <input type="number"> binds the value as a JavaScript double, which
 * is how 34.5 becomes 34.499999999999996 on its way to the server (see the
 * note on the score field in app.component.html). This function is what
 * stands in for the validation a number input would have given for free,
 * checked against the database column's actual shape instead of a float's.
 *
 * An empty string is treated as OK here on purpose: whether a value is
 * REQUIRED is a separate question, asked by Angular's own `required`
 * validator. Bundling the two would make this function lie about which rule
 * actually failed.
 */
export function checkDecimal(
  raw: string,
  options: { maxIntegerDigits: number; maxDecimalDigits: number; requirePositive: boolean }
): DecimalCheckResult {
  const value = raw.trim();
  if (value === '') {
    return { ok: true };
  }

  const match = /^(\d+)(?:\.(\d+))?$/.exec(value);
  if (!match) {
    return { ok: false, reason: 'format' };
  }

  const [, integerPart, decimalPart] = match;
  if (integerPart.length > options.maxIntegerDigits) {
    return { ok: false, reason: 'tooManyDigits' };
  }
  if (decimalPart && decimalPart.length > options.maxDecimalDigits) {
    return { ok: false, reason: 'tooManyDecimals' };
  }

  const numeric = Number(value);
  if (options.requirePositive && numeric <= 0) {
    return { ok: false, reason: 'notPositive' };
  }
  if (!options.requirePositive && numeric < 0) {
    return { ok: false, reason: 'negative' };
  }
  return { ok: true };
}

export type PasswordRejectReason = 'uppercase' | 'digit' | 'symbol';

export interface PasswordStrengthResult {
  ok: boolean;
  reason?: PasswordRejectReason;
}

/**
 * Check a password against the same three rules the backend's
 * PersonPatterns.PASSWORD_HAS_* constants enforce - deliberately as three
 * SEPARATE checks rather than one combined regex, for the reason given there:
 * a single regex that fails can only say "invalid", never which of
 * "needs an uppercase letter", "needs a digit" or "needs a symbol" is still
 * missing, and a person correcting a password one attempt at a time needs to
 * know which.
 *
 * Length is NOT checked here - `minlength` already does that natively, and
 * bundling it in would make this function lie about which rule failed, the
 * same reasoning checkDecimal's own doc comment gives for leaving presence to
 * `required`.
 */
export function checkPasswordStrength(raw: string): PasswordStrengthResult {
  if (raw === '') {
    return { ok: true };
  }
  if (!/[A-Z]/.test(raw)) {
    return { ok: false, reason: 'uppercase' };
  }
  if (!/[0-9]/.test(raw)) {
    return { ok: false, reason: 'digit' };
  }
  if (!/[^a-zA-Z0-9]/.test(raw)) {
    return { ok: false, reason: 'symbol' };
  }
  return { ok: true };
}

/**
 * Turn a validator's error object into a sentence a person reads.
 *
 * `errors` is whatever Angular's built-in validators (`required`, `minlength`,
 * `maxlength`, `pattern`) or this app's own `DecimalValidatorDirective` /
 * `PasswordStrengthValidatorDirective` produced. Only the FIRST applicable
 * error is worded - showing three error messages under one field at once
 * reads as the form shouting, not helping.
 *
 * `label` names the field in the sentence ("Username is required" rather than
 * "This field is required"), because a message that could belong to any input
 * on the page is a message the reader has to work to place.
 *
 * `patternHint`, when given, replaces the generic "is not in the right
 * format" for a `pattern` failure with the actual rule - "Letters, spaces,
 * hyphens and apostrophes only", say. It is optional because most callers of
 * this function are not pattern-validated fields at all.
 */
export function fieldErrorMessage(
  errors: ValidationErrors | null, label: string, patternHint: string | null = null
): string | null {
  if (!errors) {
    return null;
  }

  if (errors['required']) {
    return `${label} is required.`;
  }

  if (errors['minlength']) {
    const { requiredLength, actualLength } = errors['minlength'];
    return `${label} must be at least ${requiredLength} characters `
      + `(${actualLength} so far).`;
  }

  if (errors['maxlength']) {
    const { requiredLength } = errors['maxlength'];
    return `${label} must be at most ${requiredLength} characters.`;
  }

  if (errors['pattern']) {
    return patternHint ? `${label} ${patternHint}.` : `${label} is not in the right format.`;
  }

  if (errors['passwordStrength']) {
    const reason: PasswordRejectReason | undefined = errors['passwordStrength'].reason;
    switch (reason) {
      case 'uppercase':
        return `${label} must contain at least one uppercase letter.`;
      case 'digit':
        return `${label} must contain at least one digit.`;
      case 'symbol':
        return `${label} must contain at least one symbol.`;
      default:
        return `${label} is not strong enough.`;
    }
  }

  if (errors['decimal']) {
    const reason: DecimalRejectReason | undefined = errors['decimal'].reason;
    switch (reason) {
      case 'format':
        return `${label} must be a plain number, e.g. 34 or 34.5.`;
      case 'tooManyDigits':
        return `${label} is too large.`;
      case 'tooManyDecimals':
        return `${label} may have at most ${MARK_MAX_DECIMAL_DIGITS} decimal places.`;
      case 'notPositive':
        return `${label} must be greater than zero.`;
      case 'negative':
        return `${label} cannot be negative.`;
      default:
        return `${label} is not a valid number.`;
    }
  }

  return `${label} is not valid.`;
}
