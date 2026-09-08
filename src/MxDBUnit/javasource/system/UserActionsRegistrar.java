package system;

import com.mendix.core.actionmanagement.IActionRegistrator;

public class UserActionsRegistrar
{
  public void registerActions(IActionRegistrator registrator)
  {
    registrator.registerUserAction(feedbackmodule.actions.ValidateEmail.class);
    registrator.registerUserAction(feedbackmodule.actions.XSS_Sanitizer.class);
    registrator.registerUserAction(oql.actions.AddBooleanParameter.class);
    registrator.registerUserAction(oql.actions.AddDateTimeParameter.class);
    registrator.registerUserAction(oql.actions.AddDecimalParameter.class);
    registrator.registerUserAction(oql.actions.AddIntegerLongValue.class);
    registrator.registerUserAction(oql.actions.AddObjectParameter.class);
    registrator.registerUserAction(oql.actions.AddStringParameter.class);
    registrator.registerUserAction(oql.actions.CountRowsOQLStatement.class);
    registrator.registerUserAction(oql.actions.ExecuteOQLStatement.class);
    registrator.registerUserAction(oql.actions.ExportOQLToCSV.class);
    registrator.registerUserAction(oql.actions.ExportOQLToMarkdown.class);
    registrator.registerUserAction(streamingexcel.actions.ExportDataSetToExcel.class);
    registrator.registerUserAction(streamingexcel.actions.ExportOQLToExcel.class);
    registrator.registerUserAction(streamingexcel.actions.ReadNextLine.class);
    registrator.registerUserAction(streamingexcel.actions.StreamingExcelExport.class);
    registrator.registerUserAction(streamingexcel.actions.StreamingExcelImport.class);
    registrator.registerUserAction(streamingexcel.actions.WriteNextLine.class);
    registrator.registerUserAction(system.actions.VerifyPassword.class);
    registrator.registerUserAction(unittesting.actions.AssertUsingExpression.class);
    registrator.registerUserAction(unittesting.actions.FindAllUnitTests.class);
    registrator.registerUserAction(unittesting.actions.Initialize.class);
    registrator.registerUserAction(unittesting.actions.IsEnabled.class);
    registrator.registerUserAction(unittesting.actions.IsInitialized.class);
    registrator.registerUserAction(unittesting.actions.RegisterModelUpdateSubscriber.class);
    registrator.registerUserAction(unittesting.actions.ReportStepJava.class);
    registrator.registerUserAction(unittesting.actions.RunAllUnitTestsWrapper.class);
    registrator.registerUserAction(unittesting.actions.RunUnitTest.class);
    registrator.registerUserAction(unittesting.actions.StartRemoteApiServlet.class);
    registrator.registerUserAction(unittesting.actions.StartRunAllSuites.class);
    registrator.registerUserAction(unittesting.actions.TestRefreshRequired.class);
    registrator.registerUserAction(unittesting.actions.ThrowAssertionFailed.class);
    registrator.registerUserAction(unittesting.actions.UpdateTestSuiteCountersAndResult.class);
    registrator.registerUserAction(unittestutil.actions.JAV_InsertFromExcel.class);
    registrator.registerUserAction(unittestutil.actions.JAV_Test.class);
  }
}
