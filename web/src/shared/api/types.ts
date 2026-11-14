import type { components } from './schema';

// Names for the schemas of contracts/dispatch-api.yaml. schema.d.ts is generated (`pnpm api:types`);
// modules import these aliases, not the generated file.
type Schemas = components['schemas'];

export type Problem = Schemas['Problem'];
export type FieldError = Schemas['FieldError'];
export type SystemInfo = Schemas['SystemInfo'];
export type CsrfToken = Schemas['CsrfToken'];
export type LoginRequest = Schemas['LoginRequest'];
export type Role = Schemas['Role'];
export type Me = Schemas['Me'];
export type ServiceRef = Schemas['ServiceRef'];
export type UserSummary = Schemas['UserSummary'];
export type PersonRef = Schemas['PersonRef'];
export type BusinessStatus = Schemas['BusinessStatus'];
export type DeliveryStatus = Schemas['DeliveryStatus'];
export type RequestListItem = Schemas['RequestListItem'];
export type RequestPage = Schemas['RequestPage'];
export type CreateRequest = Schemas['CreateRequest'];
export type UpdateRequest = Schemas['UpdateRequest'];
export type BusinessResult = Schemas['BusinessResult'];
export type ExchangeInfo = Schemas['ExchangeInfo'];
export type RequestCard = Schemas['RequestCard'];
