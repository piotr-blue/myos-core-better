import { isMap, parseDocument, type ScalarTag } from "yaml";
import type { AccountLookupPage, LookupAccounts } from "@blue-labs/myos-sdk";

/** Bind launch-time account locators without parsing business numbers through JS Number. */
export function prepareExampleDocument(source:string, roles:{name:string;label:string}[], identities:Record<string,string>, accountId:string, representatives:Record<string,string> = {}):string {
  const doc=parseDocument(source, {
    intAsBigInt: true,
    customTags: tags => tags.map(tag => typeof tag === "object" && tag.tag === "tag:yaml.org,2002:float"
      ? {...(tag as ScalarTag), stringify: (item:any) => item.source ?? String(item.value)} : tag),
  });
  if(doc.errors.length)throw doc.errors[0];
  const clauses=doc.get("clauses",true);
  if(!isMap(clauses))throw new Error("The example must declare clauses.");
  const activation=clauses.items.find(pair=>isMap(pair.value)&&pair.value.get("type")==="Activation/Activation Clause")?.value;
  if(!isMap(activation))throw new Error("The example must declare Activation.");
  for(const role of roles) {
    const value=(identities[role.name]??accountId).trim();
    if(!value)throw new Error(`Choose an account for ${role.label}.`);
    const [party,rep]=role.name.split("__");
    const path=["parameters","parties",party,...(rep?["representatives",rep]:[])];
    const target=activation.getIn(path,true);
    if(!isMap(target))throw new Error(`Missing party definition for ${role.label}.`);
    for(const field of ["myosAccountId","accountId","email"])target.delete(field);
    if (value.includes("@")) throw new Error("Use an exact account ID, not a private login email.");
    target.set("myosAccountId",value);
    if (rep) {
      const userId = representatives[role.name]?.trim();
      if (!userId) throw new Error(`Choose a human User ID for ${role.label}.`);
      target.set("representativeId", userId);
    }
  }
  if (Object.keys(representatives).length) activation.setIn(["parameters", "frozen"], true);
  return doc.toString();
}

/** Resolve identities before provisioning a scenario that requires different legal actors. */
export async function distinctExampleAccounts(pairs:string[][], identities:Record<string,string>,
  accountId:string, lookup:(request:LookupAccounts)=>Promise<AccountLookupPage>) {
  const bound={...identities};
  const ids=new Set<string>();
  for(const role of new Set(pairs.flat())) {
    const value=(identities[role]??accountId).trim();
    if(!value)throw new Error(`Choose an account for ${role}.`);
    if (value.includes("@")) throw new Error("Use an exact account ID, not a private login email.");
    ids.add(value);
    bound[role]=value;
  }
  const requested=[...ids];
  for(let offset=0;offset<requested.length;offset+=100) {
    const accountIds=requested.slice(offset,offset+100), found=new Set<string>(), tokens=new Set<string>();
    let pageToken:string|null=null;
    do {
      const page=await lookup({accountIds,limit:50,pageToken});
      for(const item of page.items) {
        if(!accountIds.includes(item.accountId)||found.has(item.accountId))
          throw new Error("Core returned an inconsistent account lookup. Try again.");
        found.add(item.accountId);
      }
      if(!page.hasMore) break;
      if(!page.nextPageToken||tokens.has(page.nextPageToken)||page.items.length===0)
        throw new Error("Core returned an incomplete account lookup. Try again.");
      pageToken=page.nextPageToken; tokens.add(pageToken);
    } while(pageToken);
    for(const id of accountIds) if(!found.has(id)) throw new Error(`Account ${id} was not found. Choose an active account.`);
  }
  for(const [left,right] of pairs)if(bound[left]===bound[right])
    throw new Error(`This example requires different accounts for ${left} and ${right}.`);
  return bound;
}
