"""Explicit deployment protocol profiles and a shell-safe health assertion."""
import shlex

EDITION = 'AYqefXDffJR23qq1exeZN33bNVxT6ZiraXtJg9bk4Vup'
PROFILES = ('canonical', 'console-native', 'console-deployed')


def settings(profile):
    if profile not in PROFILES:
        raise ValueError('Unsupported deployment profile; retired document-local histories require replay')
    return {'edition': EDITION, 'supportedEditions': [EDITION],
            'manifest': ('examples/clause-tutorial/runtime-console-deployed.json' if profile == 'console-deployed' else 'examples/clause-tutorial/runtime-console.json' if profile == 'console-native'
                         else 'examples/runtime/canonical.json')}


def environment(profile, *, reader_prefix=None):
    manifest = settings(profile)['manifest']
    if profile in ('console-native', 'console-deployed'):
        from reader_infra import environment as reader_environment
        return 'Environment=MYOS_RUNTIME=/opt/myos-better/current/' + manifest + '\n' + reader_environment(reader_prefix)
    return '' if manifest is None else 'Environment=MYOS_RUNTIME=/opt/myos-better/current/' + manifest + '\n'


def health_script(profile, *, combined_acknowledgements=None):
    selected = settings(profile)
    policy_check = ('' if combined_acknowledgements is None else
                    ' and health.get("combinedAcknowledgements") is ' + str(combined_acknowledgements))
    if profile in ('console-native', 'console-deployed'):
        policy_check += ' and health.get("canonicalGraphs") is True and health.get("nativeAdmission") is True and health.get("timelineProcessing") is True and health.get("timelineAutomatic") is True'
    return ("import json,sys\n"
            "try: health=json.load(sys.stdin)\n"
            "except (ValueError, OSError): sys.exit(1)\n"
            + "expected=" + repr(selected) + "\n"
            + 'valid=(health.get("status")=="ok" and health.get("durable") is True '
            + 'and health.get("edition")==expected["edition"] '
            + 'and health.get("supportedEditions")==expected["supportedEditions"]' + policy_check + ')\n'
            + 'print(json.dumps(health)); sys.exit(0 if valid else 1)')


def health_check(profile, *, port=8080, combined_acknowledgements=None):
    return ('curl --fail --silent http://127.0.0.1:' + str(port) + '/health | python3 -c '
            + shlex.quote(health_script(profile, combined_acknowledgements=combined_acknowledgements)))
