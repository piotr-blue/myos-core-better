"""The HTTPS qualifier must await the submitted input's own parent outcome."""
import unittest
from unittest.mock import Mock, patch

from qualify_preview import wait_for_entry


class ParentReceiptTest(unittest.TestCase):
    def test_unrelated_updates_and_child_visibility_do_not_complete_parent_wait(self):
        client = Mock()
        client.ok.side_effect = [
            {'version': 3},
            {'input': {'control': 'refresh'}, 'outcome': {'status': 'APPLIED'}},
            {'input': {'entry': 'another-input'}, 'outcome': {'status': 'APPLIED'}},
            {'version': 4},
            {'input': {'entry': 'submitted-input'}, 'outcome': {'status': 'APPLIED'}},
        ]
        with patch('qualify_preview.time.sleep'):
            self.assertEqual(4, wait_for_entry(client, 'agreement', 'submitted-input', 1))
        paths = [call.args[0] for call in client.ok.call_args_list]
        self.assertEqual(1, paths.count('/api/documents/agreement/history/2'))
        self.assertEqual(1, paths.count('/api/documents/agreement/history/3'))

    def test_matching_rejected_input_is_not_reported_as_success(self):
        client = Mock()
        client.ok.side_effect = [
            {'version': 2},
            {'input': {'entry': 'submitted-input'}, 'outcome': {'status': 'REJECTED'}},
        ]
        with self.assertRaises(AssertionError):
            wait_for_entry(client, 'agreement', 'submitted-input', 1)


if __name__ == '__main__':
    unittest.main()
