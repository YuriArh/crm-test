import type { components } from './schema';

type Schemas = components['schemas'];

export type Me = Schemas['Me'];
export type Role = Schemas['Role'];
export type SystemInfo = Schemas['SystemInfo'];
export type UserSummary = Schemas['UserSummary'];
export type PersonRef = Schemas['PersonRef'];
export type BusinessStatus = Schemas['BusinessStatus'];
export type DeliveryStatus = Schemas['DeliveryStatus'];
export type RequestListItem = Schemas['RequestListItem'];
export type RequestPage = Schemas['RequestPage'];
export type RequestCard = Schemas['RequestCard'];
export type CreateRequest = Schemas['CreateRequest'];
export type UpdateRequest = Schemas['UpdateRequest'];
export type Problem = Schemas['Problem'];
export type FieldError = Schemas['FieldError'];
