# Description
MxDBUnit is a testing framework (an extension for the 'Unit Testing' module) designed to facilitate unit testing for database read and write operations in Mendix.  
It is being developed with the goal of enabling a testing experience using DBUnit within Mendix.
## Key Features and Functions
### Easy Test Data Preparation:  
Before executing a test, data defined in an Excel file is registered into the database in a single action.
### Database State Reset:  
Clears or initializes data before tests, ensuring that each test runs from the same clean state.
### Comparison with Expected Values:  
Easily verifies (via assertions) whether the database contents after program execution match the expected results, using data defined in an Excel file.
# Restrictions
## Currently, entity names of 30 characters or fewer are supported.
### There are plans to improve this using an alias definition sheet.
## Comparison results for entities with a large number of items can sometimes be difficult to read.
### There are plans to improve this by inserting markers to indicate the differences.
# Dependencies
### CommunityCommons Module
### UnitTesting Module
