'''Regression test suite for V1 BFD Server endpoints.

The tests within this Locust test suite hit various endpoints that were
determined to be representative of typical V1 endpoint loads. When running
this test suite, all tests in this suite will be run in parallel, with
equal weighting being applied to each.
'''

from locust import task
from common import bene_tests, contract_tests, mbi_tests, validation
from common.bene_tests import BeneTestUser
from common.contract_tests import ContractTestUser
from common.mbi_tests import MBITestUser

# Regression suite needs consistent data so we turn off table sampling prior to the
# imported modules loading their respective data from the database
bene_tests.table_sample_bene_ids = False
contract_tests.table_sample_contract_data = False
mbi_tests.table_sample_hashed_mbis = False
validation.set_validation_goal(validation.ValidationGoal.SLA_V1_BASELINE)

class BFDUser(BeneTestUser, MBITestUser, ContractTestUser):
    '''Regression test suite for V1 BFD Server endpoints.

    The tests within this Locust test suite hit various endpoints that were
    determined to be representative of typical V1 endpoint loads. When running
    this test suite, all tests in this suite will be run in parallel, with
    equal weighting being applied to each.
    '''
    
    # Do we terminate the tests when a test runs out of data and paginated URLs?
    END_ON_NO_DATA = False

    @task
    def coverage_test_id_count(self):
        '''Coverage search by ID, Paginated'''
        self._test_v1_coverage_test_id_count()

    @task
    def coverage_test_id_last_updated(self):
        '''Coverage search by ID, Last Updated'''
        self._test_v1_coverage_test_id_last_updated()

    @task
    def eob_test_id_count_type_pde(self):
        '''Explanation of Benefit search by ID, type PDE, paginated'''
        self._test_v1_eob_test_id_count_type_pde()

    @task
    def eob_test_id_last_updated_count(self):
        '''Explanation of Benefit search by ID, last updated, paginated'''
        self._test_v1_eob_test_id_last_updated_count()

    @task
    def eob_test_id_include_tax_number_last_updated(self):
        '''Explanation of Benefit search by ID, Last Updated, Include Tax Numbers'''
        self._test_v1_eob_test_id_include_tax_number_last_updated()

    @task
    def eob_test_id_last_updated(self):
        '''Explanation of Benefit search by ID, Last Updated'''
        self._test_v1_eob_test_id_last_updated()

    @task
    def eob_test_id(self):
        '''Explanation of Benefit search by ID'''
        self._test_v1_eob_test_id()

    @task
    def patient_test_coverage_contract(self):
        '''Patient search by coverage contract (all pages)'''
        self._test_v1_patient_test_coverage_contract()

    @task
    def patient_test_hashed_mbi(self):
        '''Patient search by ID, Last Updated, include MBI, include Address'''
        self._test_v1_patient_test_hashed_mbi()

    @task
    def patient_test_id_last_updated_include_mbi_include_address(self):
        '''Patient search by ID, Last Updated, include MBI, include Address'''
        self._test_v1_patient_test_id_last_updated_include_mbi_include_address()

    @task
    def patient_test_id(self):
        '''Patient search by ID'''
        self._test_v1_patient_test_id()
