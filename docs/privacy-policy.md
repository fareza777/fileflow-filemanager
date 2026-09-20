# FileFlow — Privacy Policy

**Last updated: 14 September 2026**

FileFlow is a file manager for Android. This policy explains, in plain language,
what the app does and does not do with your information.

## The short version

FileFlow does not collect your data. There is no account, no server, and no
analytics. Your files never leave your device.

## What FileFlow accesses

**Files and folders on your device.** FileFlow asks for "All files access"
(MANAGE_EXTERNAL_STORAGE) because that permission is the only way Android allows
a file manager to do its job. It is used exclusively to:

- list, search, open, copy, move, rename and delete files **you** choose;
- find large files, duplicate files and leftover junk so you can decide what to
  remove;
- run the Auto Rules you created, on the schedule you set.

FileFlow reads file names, sizes, dates and — only for images shown in a list —
the image itself, to draw a thumbnail. It never reads file contents for any
other purpose, and it never uploads anything.

**Storage statistics.** Used to show how much space is free.

## What FileFlow does not do

- No account, sign-up, or login.
- No analytics, crash reporting, or telemetry of any kind.
- No advertising identifiers used by us — see the Advertising section below.
- No cloud sync, backup, or upload. There is no backend at all.
- No access to your contacts, camera, microphone, location, or calendar.

## Where your data lives

Everything FileFlow remembers — your rules, rule history, trash index, settings —
is stored in a private database inside the app on your device. Uninstalling the
app removes it. You can clear rule history at any time from Rules → History.

Files you delete go to **FileFlow Trash**, a hidden `.fileflow/trash` folder on
your own storage. They are removed permanently after the retention period you
choose in Settings (30 days by default), or immediately if you empty the trash.

## Advertising

FileFlow shows a small banner ad on the Home, Tools and Settings screens. Ads
never appear in the file browser, search results, rule previews, or anywhere you
are working with files.

Ads are served by **Google AdMob**. To serve ads, Google may collect and process
data as described in Google's own privacy policy
(https://policies.google.com/technologies/partner-sites), including a device
advertising identifier and coarse, IP-derived location. FileFlow itself does not
receive or store any of this data.

Where required by law (for example in the EEA and the UK), FileFlow asks for your
consent through Google's User Messaging Platform **before** any ad is requested.
If you decline, no ads are requested at all. You can change your choice at any
time from the device's Ad settings.

## Permissions and why each one exists

| Permission | Why |
| --- | --- |
| All files access (`MANAGE_EXTERNAL_STORAGE`) | Required to browse and organise files outside the app's own folder — the core function of a file manager. |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | The same access on Android 9 and older. |
| `POST_NOTIFICATIONS` | Optional. Only used to report that a scheduled rule finished. |
| `RECEIVE_BOOT_COMPLETED` | Re-arms your scheduled rules after the device restarts. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Used only by Google AdMob to load ads. |
| `AD_ID` | Used only by Google AdMob. |

## Children

FileFlow is a general-purpose utility and is not directed at children. It does
not knowingly collect information from anyone, including children.

## Changes

If this policy changes, the new version will be published at this address and
the "last updated" date above will change.

## Contact

Questions about this policy: **fajar@example.com** *(replace with your real
support address before publishing)*.
